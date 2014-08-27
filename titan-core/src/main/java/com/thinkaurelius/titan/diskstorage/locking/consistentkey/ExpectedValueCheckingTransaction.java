package com.thinkaurelius.titan.diskstorage.locking.consistentkey;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.thinkaurelius.titan.core.attribute.Duration;
import com.thinkaurelius.titan.diskstorage.*;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.*;
import com.thinkaurelius.titan.diskstorage.locking.LocalLockMediator;
import com.thinkaurelius.titan.diskstorage.locking.Locker;
import com.thinkaurelius.titan.diskstorage.locking.PermanentLockingException;
import com.thinkaurelius.titan.diskstorage.util.BackendOperation;
import com.thinkaurelius.titan.diskstorage.util.BufferUtil;
import com.thinkaurelius.titan.diskstorage.util.KeyColumn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * A {@link StoreTransaction} that supports locking via
 * {@link LocalLockMediator} and writing and reading lock records in a
 * {@link ExpectedValueCheckingStore}.
 * <p/>
 * <p/>
 * <b>This class is not safe for concurrent use by multiple threads.
 * Multithreaded access must be prevented or externally synchronized.</b>
 */
public class ExpectedValueCheckingTransaction implements StoreTransaction {

    private static final Logger log = LoggerFactory.getLogger(ExpectedValueCheckingTransaction.class);

    /**
     * This variable starts false.  It remains false during the
     * locking stage of a transaction.  It is set to true at the
     * beginning of the first mutate/mutateMany call in a transaction
     * (before performing any writes to the backing store).
     */
    private boolean isMutationStarted;

    /**
     * Transaction for reading and writing locking-related metadata. Also used
     * for reading expected values provided as arguments to
     * {@link KeyColumnValueStore#acquireLock(StaticBuffer, StaticBuffer, StaticBuffer, StoreTransaction)}
     */
    private final StoreTransaction strongConsistentTx;

    /**
     * Transaction for reading and writing client data. No guarantees about
     * consistency strength.
     */
    private final StoreTransaction inconsistentTx;
    private final Duration maxReadTime;

    private final Map<ExpectedValueCheckingStore, Map<KeyColumn, StaticBuffer>> expectedValuesByStore =
            new HashMap<ExpectedValueCheckingStore, Map<KeyColumn, StaticBuffer>>();

    public ExpectedValueCheckingTransaction(StoreTransaction inconsistentTx, StoreTransaction strongConsistentTx, Duration maxReadTime) {
        this.inconsistentTx = inconsistentTx;
        this.strongConsistentTx = strongConsistentTx;
        this.maxReadTime = maxReadTime;
    }

    @Override
    public void rollback() throws BackendException {
        deleteAllLocks();
        inconsistentTx.rollback();
        strongConsistentTx.rollback();
    }

    @Override
    public void commit() throws BackendException {
        inconsistentTx.commit();
        deleteAllLocks();
        strongConsistentTx.commit();
    }

    /**
     * Tells whether this transaction has been used in a
     * {@link ExpectedValueCheckingStore#mutate(StaticBuffer, List, List, StoreTransaction)}
     * call. When this returns true, the transaction is no longer allowed in
     * calls to
     * {@link ExpectedValueCheckingStore#acquireLock(StaticBuffer, StaticBuffer, StaticBuffer, StoreTransaction)}.
     *
     * @return False until
     *         {@link ExpectedValueCheckingStore#mutate(StaticBuffer, List, List, StoreTransaction)}
     *         is called on this transaction instance. Returns true forever
     *         after.
     */
    public boolean isMutationStarted() {
        return isMutationStarted;
    }

    @Override
    public BaseTransactionConfig getConfiguration() {
        return inconsistentTx.getConfiguration();
    }

    public StoreTransaction getInconsistentTx() {
        return inconsistentTx;
    }

    public StoreTransaction getConsistentTx() {
        return strongConsistentTx;
    }

    void storeExpectedValue(ExpectedValueCheckingStore store, KeyColumn lockID, StaticBuffer value) {
        Preconditions.checkNotNull(store);
        Preconditions.checkNotNull(lockID);

        lockedOn(store);
        Map<KeyColumn, StaticBuffer> m = expectedValuesByStore.get(store);
        assert null != m;
        if (m.containsKey(lockID)) {
            log.debug("Multiple expected values for {}: keeping initial value {} and discarding later value {}",
                    new Object[]{lockID, m.get(lockID), value});
        } else {
            m.put(lockID, value);
            log.debug("Store expected value for {}: {}", lockID, value);
        }
    }

    /**
     * If {@code !}{@link #isMutationStarted()}, check all locks and expected
     * values, then mark the transaction as started.
     * <p>
     * If {@link #isMutationStarted()}, this does nothing.
     *
     * @throws com.thinkaurelius.titan.diskstorage.BackendException
     *
     * @return true if this transaction holds at least one lock, false if the
     *         transaction holds no locks
     */
    boolean prepareForMutations() throws BackendException {
        if (!isMutationStarted()) {
            checkAllLocks();
            checkAllExpectedValues();
            mutationStarted();
        }
        return !expectedValuesByStore.isEmpty();
    }

    /**
     * Check all locks attempted by earlier
     * {@link KeyColumnValueStore#acquireLock(StaticBuffer, StaticBuffer, StaticBuffer, StoreTransaction)}
     * calls using this transaction.
     *
     * @throws com.thinkaurelius.titan.diskstorage.BackendException
     */
    void checkAllLocks() throws BackendException {
        StoreTransaction lt = getConsistentTx();
        for (ExpectedValueCheckingStore store : expectedValuesByStore.keySet()) {
            Locker locker = store.getLocker();
            // Ignore locks on stores without a locker
            if (null == locker)
                continue;
            locker.checkLocks(lt);
        }
    }

    /**
     * Check that all expected values saved from earlier
     * {@link KeyColumnValueStore#acquireLock(StaticBuffer, StaticBuffer, StaticBuffer, StoreTransaction)}
     * calls using this transaction.
     *
     * @throws com.thinkaurelius.titan.diskstorage.BackendException
     */
    void checkAllExpectedValues() throws BackendException {
        for (final ExpectedValueCheckingStore store : expectedValuesByStore.keySet()) {
            final Map<KeyColumn, StaticBuffer> m = expectedValuesByStore.get(store);
            for (final KeyColumn kc : m.keySet()) {
                checkSingleExpectedValue(kc, m.get(kc), store);
            }
        }
    }

    /**
     * Signals the transaction that it has been used in a call to
     * {@link ExpectedValueCheckingStore#mutate(StaticBuffer, List, List, StoreTransaction)}
     * . This transaction can't be used in subsequent calls to
     * {@link ExpectedValueCheckingStore#acquireLock(StaticBuffer, StaticBuffer, StaticBuffer, StoreTransaction)}
     * .
     * <p/>
     * Calling this method at the appropriate time is handled automatically by
     * {@link ExpectedValueCheckingStore}. Titan users don't need to call this
     * method by hand.
     */
    private void mutationStarted() {
        isMutationStarted = true;
    }

    private void lockedOn(ExpectedValueCheckingStore store) {
        Map<KeyColumn, StaticBuffer> m = expectedValuesByStore.get(store);

        if (null == m) {
            m = new HashMap<KeyColumn, StaticBuffer>();
            expectedValuesByStore.put(store, m);
        }
    }

    private void checkSingleExpectedValue(final KeyColumn kc,
                                          final StaticBuffer ev, final ExpectedValueCheckingStore store) throws BackendException {
        BackendOperation.executeDirect(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                checkSingleExpectedValueUnsafe(kc, ev, store);
                return true;
            }
            @Override
            public String toString() {
                return "ExpectedValueChecking";
            }
        },maxReadTime);
    }

    private void checkSingleExpectedValueUnsafe(final KeyColumn kc,
                                                final StaticBuffer ev, final ExpectedValueCheckingStore store) throws BackendException {
        final StaticBuffer nextBuf = BufferUtil.nextBiggerBuffer(kc.getColumn());
        KeySliceQuery ksq = new KeySliceQuery(kc.getKey(), kc.getColumn(), nextBuf);
        // Call getSlice on the wrapped store using the quorum+ consistency tx
        Iterable<Entry> actualEntries = store.getBackingStore().getSlice(ksq, strongConsistentTx);

        if (null == actualEntries)
            actualEntries = ImmutableList.<Entry>of();

        /*
         * Discard any columns which do not exactly match kc.getColumn().
         *
         * For example, it's possible that the slice returned columns which for
         * which kc.getColumn() is a prefix.
         */
        actualEntries = Iterables.filter(actualEntries, new Predicate<Entry>() {
            @Override
            public boolean apply(Entry input) {
                if (!input.getColumn().equals(kc.getColumn())) {
                    log.debug("Dropping entry {} (only accepting column {})", input, kc.getColumn());
                    return false;
                }
                log.debug("Accepting entry {}", input);
                return true;
            }
        });

        // Extract values from remaining Entry instances
        Iterable<StaticBuffer> actualVals = Iterables.transform(actualEntries, new Function<Entry, StaticBuffer>() {
            @Override
            public StaticBuffer apply(Entry e) {
                StaticBuffer actualCol = e.getColumnAs(StaticBuffer.STATIC_FACTORY);
                assert null != actualCol;
                assert null != kc.getColumn();
                assert 0 >= kc.getColumn().compareTo(actualCol);
                assert 0  > actualCol.compareTo(nextBuf);
                return e.getValueAs(StaticBuffer.STATIC_FACTORY);
            }
        });

        final Iterable<StaticBuffer> expectedVals;

        if (null == ev) {
            expectedVals = ImmutableList.<StaticBuffer>of();
        } else {
            expectedVals = ImmutableList.<StaticBuffer>of(ev);
        }

        if (!Iterables.elementsEqual(expectedVals, actualVals)) {
            throw new PermanentLockingException(
                    "Expected value mismatch for " + kc + ": expected="
                            + expectedVals + " vs actual=" + actualVals + " (store=" + store.getName() + ")");
        }
    }

    private void deleteAllLocks() throws BackendException {
        for (ExpectedValueCheckingStore s : expectedValuesByStore.keySet()) {
            s.deleteLocks(this);
        }
    }
}
