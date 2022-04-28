package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;

import java.util.*;

/**
 * LockManager maintains the bookkeeping for what transactions have what locks
 * on what resources and handles queuing logic. The lock manager should generally
 * NOT be used directly: instead, code should call methods of LockContext to
 * acquire/release/promote/escalate locks.
 * 对lock和transaction进行的所有操作的可能，工具class
 *
 * The LockManager is primarily concerned with the mappings between
 * transactions, resources, and locks, and does not concern itself with multiple
 * levels of granularity. Multigranularity is handled by LockContext instead.
 *
 * Each resource the lock manager manages has its own queue of LockRequest
 * objects representing a request to acquire (or promote/acquire-and-release) a
 * lock that could not be satisfied at the time. This queue should be processed
 * every time a lock on that resource gets released, starting from the first
 * request, and going in order until a request cannot be satisfied. Requests
 * taken off the queue should be treated as if that transaction had made the
 * request right after the resource was released in absence of a queue (i.e.
 * removing a request by T1 to acquire X(db) should be treated as if T1 had just
 * requested X(db) and there were no queue on db: T1 should be given the X lock
 * on db, and put in an unblocked state via Transaction#unblock).
 *
 * This does mean that in the case of:
 *    queue: S(A) X(A) S(A)
 * only the first request should be removed from the queue when the queue is
 * processed.
 */
public class LockManager {
    // transactionLocks is a mapping from transaction number to a list of lock
    // objects held by that transaction.
    private Map<Long, List<Lock>> transactionLocks = new HashMap<>();

    // resourceEntries is a mapping from resource names to a ResourceEntry
    // object, which contains a list of Locks on the object, as well as a
    // queue for requests on that resource.
    private Map<ResourceName, ResourceEntry> resourceEntries = new HashMap<>();

    // A ResourceEntry contains the list of locks on a resource, as well as
    // the queue for requests for locks on the resource.
    private class ResourceEntry {
        // List of currently *granted* locks on the resource.
        List<Lock> locks = new ArrayList<>();
        // Queue for yet-to-be-satisfied lock requests on this resource.
        Deque<LockRequest> waitingQueue = new ArrayDeque<>();

        // Below are a list of helper methods we suggest you implement.
        // You're free to modify their type signatures, delete, or ignore them.

        /**
         * Check if `lockType` is compatible with preexisting locks. Allows
         * conflicts for locks held by transaction with id `except`, which is
         * useful when a transaction tries to replace a lock it already has on
         * the resource.
         */
        public boolean checkCompatible(LockType lockType, long except) {
            for (Lock weH: locks) {
                if (!LockType.compatible(weH.lockType, lockType) && weH.transactionNum != except) {
                    return false;
                }
            }
            // TODO(proj4_part1): implement
            return true;
        }

        /**
         * Gives the transaction the lock `lock`. Assumes that the lock is
         * compatible. Updates lock on resource if the transaction already has a
         * lock.
         */
        public void grantOrUpdateLock(Lock lock) {
            if (transactionLocks.containsKey(lock.transactionNum)) {
                transactionLocks.get(lock.transactionNum).add(lock);
            } else {
                List<Lock> newList = new ArrayList<Lock>();
                newList.add(lock);
                transactionLocks.put(lock.transactionNum, newList);
            }

            Boolean hasALock = false;
            for (Lock weH: locks) {
                if (weH.transactionNum.equals(lock.transactionNum)) {
                    weH.lockType = lock.lockType;
                    hasALock = true;
                }
            }
            if (!hasALock) {
                locks.add(lock);
            }
            // TODO(proj4_part1): implement
            return;
        }

        /**
         * Releases the lock `lock` and processes the queue. Assumes that the
         * lock has been granted before.
         */
        public void releaseLock(Lock lock) {
            locks.remove(lock);
            List<Lock> currentTransaction = transactionLocks.get(lock.transactionNum);
            currentTransaction.remove(lock);
            transactionLocks.put(lock.transactionNum, currentTransaction);
            processQueue();
            // TODO(proj4_part1): implement
            return;
        }

        /**
         * Adds `request` to the front of the queue if addFront is true, or to
         * the end otherwise.
         */
        public void addToQueue(LockRequest request, boolean addFront) {
            if (addFront) {
                waitingQueue.addFirst(request);
            } else {
                waitingQueue.addLast(request);
            }
            // TODO(proj4_part1): implement
            return;
        }

        /**
         * Grant locks to requests from front to back of the queue, stopping
         * when the next lock cannot be granted. Once a request is completely
         * granted, the transaction that made the request can be unblocked.
         */
        private void processQueue() {
            Iterator<LockRequest> requests = waitingQueue.iterator();
            if (requests.hasNext()) {
                LockRequest request = requests.next();
                // use checkcompatible() to check if they could be granted
                if (checkCompatible(request.lock.lockType,request.transaction.getTransNum())) {
                    // The request at the front of the queue is considered,
                    // and if it doesn't conflict with any of the existing locks on the resource,
                    // but this transaction himself, cuz compatibility is between two different transactions.
                    // it should be removed from the queue and:
                    waitingQueue.pop();
                    grantOrUpdateLock(request.lock); // the transaction that made the request should be given the lock
                    // and grant it
                    for (Lock needToRel: request.releasedLocks) {
                        releaseLock(needToRel);// any locks that the request stated should be released are released
                    }
                    request.transaction.unblock();
                }
            }
            // TODO(proj4_part1): implement
            return;
        }

        /**
         * Gets the type of lock `transaction` has on this resource.
         */
        public LockType getTransactionLockType(long transaction) {
            for (Lock weH: locks) {
                if (transaction == weH.transactionNum) {
                    return weH.lockType;
                }
            }
            // TODO(proj4_part1): implement
            return LockType.NL;
        }

        @Override
        public String toString() {
            return "Active Locks: " + Arrays.toString(this.locks.toArray()) +
                    ", Queue: " + Arrays.toString(this.waitingQueue.toArray());
        }
    }

    // You should not modify or use this directly.
    private Map<String, LockContext> contexts = new HashMap<>();

    /**
     * Helper method to fetch the resourceEntry corresponding to `name`.
     * Inserts a new (empty) resourceEntry into the map if no entry exists yet.
     */
    private ResourceEntry getResourceEntry(ResourceName name) {
        resourceEntries.putIfAbsent(name, new ResourceEntry());
        return resourceEntries.get(name);
    }

    /**
     * Acquire a `lockType` lock on `name`, for transaction `transaction`, and
     * releases all locks on `releaseNames` held by the transaction after
     * acquiring the lock in one atomic action.
     *
     * Error checking must be done before any locks are acquired or released. If
     * the new lock is not compatible with another transaction's lock on the
     * resource, the transaction is blocked and the request is placed at the
     * FRONT of the resource's queue. // why? cuz AcquireAndRelease we set it to have the prority in quene.
     *
     * Locks on `releaseNames` should be released only after the requested lock
     * has been acquired. The corresponding queues should be processed.
     *
     * An acquire-and-release that releases an old lock on `name` should NOT
     * change the acquisition time of the lock on `name`, i.e. if a transaction
     * acquired locks in the order: S(A), X(B), acquire X(A) and release S(A),
     * the lock on A is considered to have been acquired before the lock on B.
     *
     * @throws DuplicateLockRequestException if a lock on `name` is already held
     * by `transaction` and isn't being released
     * @throws NoLockHeldException if `transaction` doesn't hold a lock on one
     * or more of the names in `releaseNames`
     */
    public void acquireAndRelease(TransactionContext transaction, ResourceName name,
                                  LockType lockType, List<ResourceName> releaseNames)
            throws DuplicateLockRequestException, NoLockHeldException {
        // TODO(proj4_part1): implement
        // You may modify any part of this method. You are not required to keep
        // all your code within the given synchronized block and are allowed to
        // move the synchronized block elsewhere if you wish.
        boolean shouldBlock = false;
        synchronized (this) {
            ResourceEntry thisEntry = getResourceEntry(name);
            if (thisEntry.getTransactionLockType(transaction.getTransNum()) == lockType && !releaseNames.contains(name))
                throw new DuplicateLockRequestException("There is already a lock by " + transaction.getTransNum()
                        + " on " + name.toString());
            for (ResourceName thisRe : releaseNames) {
                if (getResourceEntry(thisRe).getTransactionLockType(transaction.getTransNum()) == LockType.NL)
                    throw new NoLockHeldException("There is no lock by" + transaction.getTransNum() +
                            " on " + thisRe.toString());
            }
            if (!thisEntry.checkCompatible(lockType, transaction.getTransNum())) {
                shouldBlock = true;
                //To block a transaction, call Transaction#prepareBlock inside the synchronized block,
                // and then call Transaction#block outside the synchronized block.
                // The Transaction#prepareBlock needs to be in the synchronized block
                // to avoid a race condition where the transaction may be dequeued
                // between the time it leaves the synchronized block and the time
                // it actually blocks.
                transaction.prepareBlock();
                thisEntry.addToQueue(new LockRequest(transaction, new Lock(name, lockType, transaction.getTransNum())), true);
            } else {
                for (int j = 0; j < releaseNames.size(); j++) {
                    ResourceName thisRe = releaseNames.get(j);
                    Lock releasingLock;
                    for (int i = 0; i < getResourceEntry(thisRe).locks.size(); i++) {
                        Lock thisLock = getResourceEntry(thisRe).locks.get(i);
                        if (thisLock.transactionNum.equals(transaction.getTransNum())) {
                            releasingLock = thisLock;
                            transactionLocks.get(transaction.getTransNum()).remove(releasingLock);
                            getResourceEntry(thisRe).releaseLock(releasingLock);
                            getResourceEntry(thisRe).processQueue();
                        }
                    }
                }
                thisEntry.grantOrUpdateLock(new Lock(name, lockType, transaction.getTransNum()));
            }
        }
        if (shouldBlock) {
            transaction.block();
        }
    }

    /**
     * Acquire a `lockType` lock on `name`, for transaction `transaction`.
     *
     * Error checking must be done before the lock is acquired. If the new lock
     * is not compatible with another transaction's lock on the resource, or if there are
     * other transaction in queue for the resource, the transaction is
     * blocked and the request is placed at the **back** of NAME's queue.
     *
     * @throws DuplicateLockRequestException if a lock on `name` is held by
     * `transaction`
     */
    public void acquire(TransactionContext transaction, ResourceName name,
                        LockType lockType) throws DuplicateLockRequestException {
        // TODO(proj4_part1): implement
        boolean shouldBlock = false;
        synchronized (this) {
            ResourceEntry thisEntry = getResourceEntry(name);
            if (thisEntry.getTransactionLockType(transaction.getTransNum()) == lockType)
                throw new DuplicateLockRequestException("There is already a lock by" + transaction.getTransNum()
                        + "on" + name.toString());
            if (!thisEntry.checkCompatible(lockType, transaction.getTransNum()) || !thisEntry.waitingQueue.isEmpty()) {
                shouldBlock = true;
                transaction.prepareBlock();
                thisEntry.addToQueue(new LockRequest(transaction, new Lock(name, lockType, transaction.getTransNum())), false);
            } else thisEntry.grantOrUpdateLock(new Lock(name, lockType, transaction.getTransNum()));
        }
        if (shouldBlock) {
            transaction.block();
        }
        // only block because we want to put it in the quene (requests waiting),
        // cuz we don;t want this transition in quene to affect other transition by locks' dis-compatibility
        // every time we process a request in quene, we unblock it and let it affect the following new acquiring locks
        // to see if they should be requests or just add directly, if request, then they are blocked.!!!!!!
    }

    /**
     * Release `transaction`'s lock on `name`. Error checking must be done
     * before the lock is released.
     *
     * The resource name's queue should be processed after this call. If any
     * requests in the queue have locks to be released, those should be
     * released, and the corresponding queues also processed.
     *
     * @throws NoLockHeldException if no lock on `name` is held by `transaction`
     */
    public void release(TransactionContext transaction, ResourceName name)
            throws NoLockHeldException {
        // TODO(proj4_part1): implement
        // You may modify any part of this method.
        synchronized (this) {
            ResourceEntry thisEntry = getResourceEntry(name);
            if (getLockType(transaction, name) == LockType.NL)
                // NL is no locks
                throw new NoLockHeldException("There is no lock by" + transaction.getTransNum() +
                        "on" + name.toString());
            Lock releasingLock;
            for (int i = 0; i < thisEntry.locks.size(); i++) {
                Lock thisLock = thisEntry.locks.get(i);
                if (thisLock.transactionNum.equals(transaction.getTransNum())) {
                    releasingLock = thisLock;
                    transactionLocks.get(transaction.getTransNum()).remove(releasingLock);
                    thisEntry.releaseLock(releasingLock);
                    thisEntry.processQueue();
                }
            }
        }
    }

    /**
     * Promote a transaction's lock on `name` to `newLockType` (i.e. change
     * the transaction's lock on `name` from the current lock type to
     * `newLockType`, if its a valid substitution).
     *
     * Error checking must be done before any locks are changed. If the new lock
     * is not compatible with another transaction's lock on the resource, the
     * transaction is blocked and the request is placed at the FRONT of the
     * resource's queue.
     *
     * A lock promotion should NOT change the acquisition time of the lock, i.e.
     * if a transaction acquired locks in the order: S(A), X(B), promote X(A),
     * the lock on A is considered to have been acquired before the lock on B.
     *
     * @throws DuplicateLockRequestException if `transaction` already has a
     * `newLockType` lock on `name`
     * @throws NoLockHeldException if `transaction` has no lock on `name`
     * @throws InvalidLockException if the requested lock type is not a
     * promotion. A promotion from lock type A to lock type B is valid if and
     * only if B is substitutable for A, and B is not equal to A.
     */
    public void promote(TransactionContext transaction, ResourceName name,
                        LockType newLockType)
            throws DuplicateLockRequestException, NoLockHeldException, InvalidLockException {
        // TODO(proj4_part1): implement
        // You may modify any part of this method.
        boolean shouldBlock = false;
        synchronized (this) {
            ResourceEntry thisEntry = getResourceEntry(name);
            if (thisEntry.getTransactionLockType(transaction.getTransNum()) == newLockType)
                throw new DuplicateLockRequestException("There is already a lock by" + transaction.getTransNum()
                        + "on" + name.toString() + "which type is new one, " + newLockType.toString());
            if (thisEntry.getTransactionLockType(transaction.getTransNum()) == LockType.NL)
                // NL is no locks
                throw new NoLockHeldException("There is no lock by" + transaction.getTransNum() +
                        "on" + name.toString());
            LockType oldType = thisEntry.getTransactionLockType(transaction.getTransNum());
            if (!(oldType != newLockType && LockType.substitutable(newLockType, oldType))) {
                throw new InvalidLockException("The new type" + newLockType.toString() +
                            "is not a promotion to old lock type" + oldType.toString());
            }
            if (!thisEntry.checkCompatible(newLockType, transaction.getTransNum())) {
                shouldBlock = true;
                transaction.prepareBlock();
                thisEntry.addToQueue(new LockRequest(transaction, new Lock(name, newLockType, transaction.getTransNum())), true);
            } else {
                for (Lock oldLock: transactionLocks.get(transaction.getTransNum())) {
                    if (oldLock.name.equals(name)) {
                        oldLock.lockType = newLockType;
                    }
                }
                thisEntry.grantOrUpdateLock(new Lock(name, newLockType, transaction.getTransNum()));
            }
        }
        if (shouldBlock) {
            transaction.block();
        }
    }

    /**
     * Return the type of lock `transaction` has on `name` or NL if no lock is
     * held.
     */
    public synchronized LockType getLockType(TransactionContext transaction, ResourceName name) {
        // TODO(proj4_part1): implement
        ResourceEntry resourceEntry = getResourceEntry(name);
        LockType res = LockType.NL;
        res = resourceEntry.getTransactionLockType(transaction.getTransNum());
        return res;
    }

    /**
     * Returns the list of locks held on `name`, in order of acquisition.
     */
    public synchronized List<Lock> getLocks(ResourceName name) {
        return new ArrayList<>(resourceEntries.getOrDefault(name, new ResourceEntry()).locks);
    }

    /**
     * Returns the list of locks held by `transaction`, in order of acquisition.
     */
    public synchronized List<Lock> getLocks(TransactionContext transaction) {
        return new ArrayList<>(transactionLocks.getOrDefault(transaction.getTransNum(),
                Collections.emptyList()));
    }

    /**
     * Creates a lock context. See comments at the top of this file and the top
     * of LockContext.java for more information.
     */
    public synchronized LockContext context(String name) {
        if (!contexts.containsKey(name)) {
            contexts.put(name, new LockContext(this, null, name));
        }
        return contexts.get(name);
    }

    /**
     * Create a lock context for the database. See comments at the top of this
     * file and the top of LockContext.java for more information.
     */
    public synchronized LockContext databaseContext() {
        return context("database");
    }
}
