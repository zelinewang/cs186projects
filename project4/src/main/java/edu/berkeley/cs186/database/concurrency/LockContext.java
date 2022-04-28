package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LockContext wraps around LockManager to provide the hierarchical structure
 * of multigranularity locking. Calls to acquire/release/etc. locks should
 * be mostly done through a LockContext, which provides access to locking
 * methods at a certain point in the hierarchy (database, table X, etc.)
 * 利用LockManger去管理现有所有source中一起运行的不同的lock，每个source运行一个lock，这就是multigranularity
 *
 * Intro and ReCall from the concurrency and locks:::******************************
 * ********************************************************************************
 * Suppose another transaction just needs to access a few data items from a database,
 * so locking the entire database seems to be unnecessary moreover it may cost us a loss of Concurrency,
 * which was our primary goal in the first place. To bargain between Efficiency and Concurrency.
 * Use Granularity.
 * Multiple Granularity means hierarchically breaking up the database into blocks
 * that can be locked and can be tracked needs what needs to lock and in what fashion.
 * Such a hierarchy can be represented graphically as a tree.
 *
 *  if transaction Ti gets an explicit lock on file Fc in exclusive mode,
 *  then it has an implicit lock in exclusive mode on all the records belonging to that file.
 *  It does not need to lock the individual records of Fc explicitly. this is the main difference between
 *  Tree-Based Locking and Hierarchical locking for multiple granularities.
 *
 *  The multiple-granularity locking protocol uses the intention lock modes to ensure serializability.
 *  (compatibility matrix./.../..../)
 *  multiple-granularity protocol requires that locks be acquired in top-down (root-to-leaf) order,
 *  whereas locks must be released in bottom-up (leaf to-root) order. Say transaction T1 reads record Ra2 in file Fa. Then, T2 needs to lock the database, area A1, and Fa in IS mode (and in that order), and finally to lock Ra2 in S mode.
 *  ********************************************************************************
 *  ********************************************************************************
 *
 *
 *  ********************************************************************************
 *  ********************************************************************************
 *  OUTLINE:
 *  The LOCKMANAGER object manages all the locks, treating each resource as independent
 *  (it doesn't consider the resource hierarchy at all). This level is responsible queuing logic,
 *  blocking/unblocking transactions as necessary, and is the single source of authority on
 *  whether a transaction has a certain lock.
 *
 *  A collection of LOCKCONTEXT objects, which each represent a single lockable object
 *  (e.g. a page or a table) lies on top of the LockManager. The LockContext objects are connected
 *  according to the hierarchy (e.g. a LockContext for a table has the database context as its parent,
 *  and its pages' contexts as children). The LockContext objects all share a single LockManager,
 *  and each context enforces multigranularity constraints on its methods (e.g. an exception
 *  will be thrown if a transaction attempts to request X(table) without IX(database)).
 *
 *  A declarative layer (LOCKUTILL) lies on top of the collection of LockContext objects, and is responsible
 *  for acquiring all the intent locks needed for each S or X request that the database uses (e.g. if S(page)
 *  is requested, this layer would be responsible for requesting IS(database), IS(table) if necessary).
 *  ********************************************************************************
 *  ********************************************************************************
 *  All Operations in LockContext is simply operations in LockManager but considered in DBMS tree system,
 *  with a hierarchical structure!!
 *
 */
public class LockContext {
    // You should not remove any of these fields. You may add additional
    // fields/methods as you see fit.

    // The underlying lock manager.
    protected final LockManager lockman;

    // The parent LockContext object, or null if this LockContext is at the top of the hierarchy.
    protected final LockContext parent;

    // The name of the resource this LockContext represents.
    protected ResourceName name;

    // Whether this LockContext is readonly. If a LockContext is readonly, acquire/release/promote/escalate should
    // throw an UnsupportedOperationException.
    protected boolean readonly;

    // A mapping between transaction numbers, and the number of locks on children of this LockContext
    // that the transaction holds.
    protected final Map<Long, Integer> numChildLocks;

    // You should not modify or use this directly.
    protected final Map<String, LockContext> children;

    // Whether or not any new child LockContexts should be marked readonly.
    protected boolean childLocksDisabled;

    public LockContext(LockManager lockman, LockContext parent, String name) {
        this(lockman, parent, name, false);
    }

    protected LockContext(LockManager lockman, LockContext parent, String name,
                          boolean readonly) {
        this.lockman = lockman;
        this.parent = parent;
        if (parent == null) {
            this.name = new ResourceName(name);
        } else {
            this.name = new ResourceName(parent.getResourceName(), name);
        }
        this.readonly = readonly;
        this.numChildLocks = new ConcurrentHashMap<>();
        this.children = new ConcurrentHashMap<>();
        this.childLocksDisabled = readonly;
    }

    /**
     * Gets a lock context corresponding to `name` from a lock manager.
     */
    public static LockContext fromResourceName(LockManager lockman, ResourceName name) {
        Iterator<String> names = name.getNames().iterator();
        // name is a list of strings, iterator goes through these strings
        LockContext ctx;
        String n1 = names.next(); // the first string of name
        // get the context of first name (database), if there is no context of database, create a new one
        ctx = lockman.context(n1);
        while (names.hasNext()) {
            // keep looking
            String n = names.next();
            // get the childContext of parent context
            ctx = ctx.childContext(n);
        }
        return ctx;
    }

    /**
     * Get the name of the resource that this lock context pertains to.
     */
    public ResourceName getResourceName() {
        return name;
    }

    /**
     * Acquire a `lockType` lock, for transaction `transaction`.
     * On this current lock context, acquire a lock for transaction
     *
     * Note: you must make any necessary updates to numChildLocks, or else calls
     * to LockContext#getNumChildren will not work properly.
     *
     * @throws InvalidLockException if the request is invalid
     * we prohibit acquiring an IS/S lock if an ancestor has SIX,
     * @throws DuplicateLockRequestException if a lock is already held by the
     * transaction.
     * @throws UnsupportedOperationException if context is readonly
     */
    public void acquire(TransactionContext transaction, LockType lockType)
            throws InvalidLockException, DuplicateLockRequestException {
        // TODO(proj4_part2): implement
        if (lockman.getLockType(transaction, getResourceName()) != LockType.NL) {
            // if there is already a lock from transaction at this level (at this resource)
            throw new DuplicateLockRequestException("There is already a lock at this context.");
        }
        // if you want to require such a type at this level from this transaction
        // it has to be NL before you require!!!!!
        if (readonly && (lockType.equals(LockType.X) || lockType.equals(LockType.IX))) {
            // if it's X or IX, it's contradicted with READONLY.
            throw new UnsupportedOperationException("Context is read only.");
        }
        if (hasSIXAncestor(transaction) && (lockType.equals(LockType.S) || lockType.equals(LockType.IS))) {
            throw new InvalidLockException("Have SIX ancestor. Can't acquire S or IS under SIX");
        }
        // make sure it's valid to acquire this type at this level!!!!
        // only see parent effective type cuz this type is already NL by DuplicateLockRequestException.
        // If explicit NL,
        // then if there is X in ancestor then, effective is X
        //      if there is S/SIX in ancestor then effective is S
        // only NL/IS/IX in ancestors then effective is NL
        if (parent != null && !LockType.canBeParentLock(parentContext().getExplicitLockType(transaction), lockType)) {
            // can't use Effective type instead of parentExplicitType/parentType here, cuz if effectiveType is S, but we only have SIX in ancestor,
            // it's alright to have a new X under SIX
            // But if it's only IS but not SIX, then a new X under IS, is not acceptable.

            // only invalid when it's not none and can not be the parent of this acquiring type
            throw new InvalidLockException("Have invalid ancestor.");
        }
        lockman.acquire(transaction, name, lockType);
        numChildLocks.put(transaction.getTransNum(), 0);
        addNumChildLocks(transaction, parent);
    }
    private void addNumChildLocks(TransactionContext transaction, LockContext parent) {
        if (parent == null) { return; }
        int hasLocks = parent.numChildLocks.getOrDefault(transaction.getTransNum(), 0);
        parent.numChildLocks.put(transaction.getTransNum(), hasLocks + 1);
        addNumChildLocks(transaction, parent.parent); // make changes to all the numchildlocks of all the ancestors.
    }
    private void delNumChildLocks(TransactionContext transaction, LockContext parent) {
        if (parent == null) { return; }
        int hasLocks = parent.numChildLocks.getOrDefault(transaction.getTransNum(), 0);
        if (hasLocks == 0) { return; }
        parent.numChildLocks.put(transaction.getTransNum(), hasLocks - 1);
        delNumChildLocks(transaction, parent.parent);
    }

    /**
     * Release `transaction`'s lock on `name`.
     *
     * Note: you *must* make any necessary updates to numChildLocks, or
     * else calls to LockContext#getNumChildren will not work properly.
     *
     * @throws NoLockHeldException if no lock on `name` is held by `transaction`
     * @throws InvalidLockException if the lock cannot be released because
     * doing so would violate multigranularity locking constraints
     * @throws UnsupportedOperationException if context is readonly
     */
    public void release(TransactionContext transaction)
            throws NoLockHeldException, InvalidLockException {
        // TODO(proj4_part2): implement
        if (readonly) {
            throw new UnsupportedOperationException("Context is read only.");
        }
        // Invalid cuz you can't edit readonly content
        if (lockman.getLockType(transaction, name).equals(LockType.NL)) {
            throw new NoLockHeldException("There is no lock on " + name.toString());
        }
        if (!numChildLocks.getOrDefault(transaction.getTransNum(), 0).equals(0)) {
            // if it doesn't have any child, it could be released
            // but if it still has some children we can't release it!!!!!
            throw new InvalidLockException("The lock cannot be released.");
        }
        lockman.release(transaction, name);
        delNumChildLocks(transaction, parent);
    }

    /**
     * Promote `transaction`'s lock to `newLockType`. For promotion to SIX from
     * IS/IX, all S and IS locks on descendants must be simultaneously
     * released. The helper function sisDescendants may be helpful here.
     *
     * Note: you *must* make any necessary updates to numChildLocks, or else
     * calls to LockContext#getNumChildren will not work properly.
     *
     *
     *  Promoting IS to X is not a valid operation because X locks are not a valid substitution for IS locks.
     *  Promotion to SIX locks from IS/IX locks in LockContext is a special case and is allowed.
     *
     * @throws DuplicateLockRequestException if `transaction` already has a
     * `newLockType` lock at this level (at this resource)
     * @throws NoLockHeldException if `transaction` has no lock
     * @throws InvalidLockException if the requested lock type is not a
     * promotion or promoting would cause the lock manager to enter an invalid
     * state (e.g. IS(parent), X(child)). A promotion from lock type A to lock
     * type B is valid if B is substitutable for A and B is not equal to A, or
     * if B is SIX and A is IS/IX/S, and invalid otherwise. hasSIXAncestor may
     * be helpful here.
     * @throws UnsupportedOperationException if context is readonly
     */
    public void promote(TransactionContext transaction, LockType newLockType)
            throws DuplicateLockRequestException, NoLockHeldException, InvalidLockException {
        // TODO(proj4_part2): implement
        if (readonly) { throw new UnsupportedOperationException("Xact is readonly."); }
        if (lockman.getLockType(transaction, name).equals(newLockType)) {
            throw new DuplicateLockRequestException("Xact has existed lock.");
        }
        if (lockman.getLockType(transaction, name).equals(LockType.NL)) {
            throw new NoLockHeldException("There is no lock on Xact");
        }
        LockType currentLockType = lockman.getLockType(transaction, name);
        if (newLockType == LockType.SIX) {
            if (currentLockType != LockType.IS &&
                    currentLockType != LockType.IX &&
                    currentLockType != LockType.S) {
                throw new InvalidLockException("New Type is SIX but current type is not IS/IX/S");
            }
        } else {
            if (!LockType.substitutable(newLockType, currentLockType) || currentLockType == newLockType) {
                throw new InvalidLockException("The new type is not a promotion to current lock");
            }
        }
        Boolean ifValid = true;
        LockContext currentParent = this.parentContext();
        while (currentParent != null) {
            if (!LockType.canBeParentLock(lockman.getLockType(transaction, currentParent.getResourceName()),
                    newLockType)) {
                ifValid = false;
            }
            currentParent = currentParent.parentContext();
        }
        if (!ifValid) {
            throw new InvalidLockException("After changing to new type, it's not compatible with ancestors anymore");
        }

        if (newLockType == LockType.SIX) {
            List<ResourceName> thisNameWithOldType = new ArrayList<>();
            thisNameWithOldType.add(name);
            // use Acquire and Release, cuz Promote was only used when New could substitute Old,
            // but now we want SIX replace IX, which can't work in Promote()
            // So we use acquireAndRelease() in this special case!!!!!!!!!!!
            // release only one name (oldTypeLock), acquire NewTypeLock
            if (currentLockType == LockType.IS || currentLockType == LockType.IX) {
                for (ResourceName thisName: sisDescendants(transaction)) {
                    thisNameWithOldType.add(thisName);
                }
            }
            lockman.acquireAndRelease(transaction, name, LockType.SIX, thisNameWithOldType);
            return;
        } else{
            lockman.promote(transaction, name, newLockType);
        }
    }

    /**
     * Escalate `transaction`'s lock from descendants of this context to this
     * level, using either an S or X lock. There should be no descendant locks
     * after this call, and every operation valid on descendants of this context
     * before this call must still be valid. You should only make *one* mutating
     * call to the lock manager, and should only request information about
     * TRANSACTION from the lock manager.
     *
     * For example, if a transaction has the following locks:
     *
     *                    IX(database)
     *                    /         \
     *               IX(table1)    S(table2)
     *                /      \
     *    S(table1 page3)  X(table1 page5)
     *
     * then after table1Context.escalate(transaction) is called, we should have:
     *
     *                    IX(database)
     *                    /         \
     *               X(table1)     S(table2)
     *
     * You should not make any mutating calls if the locks held by the
     * transaction do not change (such as when you call escalate multiple times
     * in a row).
     *
     * Note: you *must* make any necessary updates to numChildLocks of all
     * relevant contexts, or else calls to LockContext#getNumChildren will not
     * work properly.
     *
     * if we only had IS/S locks, we should escalate to S, not X!!!!!!!
     *
     * since we are only escalating to S or X, a transaction that only has IS(database) would escalate to S(database).
     * Though a transaction that only has IS(database) technically has no locks at lower levels, the only point
     * in keeping an intent lock at this level would be to acquire a normal lock at a lower level, and the point
     * in escalating is to avoid having locks at a lower level. Therefore, we don't allow escalating to intent locks
     * (IS/IX/SIX).
     *
     * @throws NoLockHeldException if `transaction` has no lock at this level
     * @throws UnsupportedOperationException if context is readonly
     */
    public void escalate(TransactionContext transaction) throws NoLockHeldException {
        // TODO(proj4_part2): implement
        if (readonly) {
            throw new UnsupportedOperationException("This lock is readonly");
        }
        LockType currentType = lockman.getLockType(transaction, getResourceName());
        if (currentType == LockType.NL) {
            throw new NoLockHeldException("There is no lock at this level from this transaction:" + transaction.toString());
        }

        int NumChildren = 0;
        List<ResourceName> releaseLocks = new ArrayList<>();
        releaseLocks.add(name);
        List<Lock> locks = lockman.getLocks(transaction);
        for (Lock l: locks) {
            ResourceName rname = l.name;
            if (rname.isDescendantOf(name)) {
                NumChildren += 1;
                releaseLocks.add(rname);
            }
        }

        // only consider original is IS/IX/SIX
        // cuz if S or X, doesn't have any child and nothing changes, no need to call to LockManager
        // if NL then NOLOCKException
        if (currentType == LockType.IS) {
            // escalate to S
            lockman.acquireAndRelease(transaction, getResourceName(), LockType.S, releaseLocks);
        } else if (currentType == LockType.IX || currentType == LockType.SIX){
            // currentType is SIX/IX/X then escalate to X
            lockman.acquireAndRelease(transaction, getResourceName(), LockType.X, releaseLocks);
        }
        numChildLocks.put(transaction.getTransNum(), 0);
        while (NumChildren != 0) {
            delNumChildLocks(transaction, parent);
            NumChildren -= 1;
        }
        return;
    }

    /**
     * Get the type of lock that `transaction` holds at this level, or NL if no
     * lock is held at this level.
     *
     * For example, if a transaction has X(db), dbContext.getExplicitLockType(transaction) should return X,
     * but tableContext.getExplicitLockType(transaction) should return NL (no lock explicitly held).
     */
    public LockType getExplicitLockType(TransactionContext transaction) {
        if (transaction == null) return LockType.NL;
        // TODO(proj4_part2): implement
        return lockman.getLockType(transaction, name);
    }

    /**
     * Gets the type of lock that the transaction has at this level, either
     * implicitly (e.g. explicit S lock at higher level implies S lock at this
     * level) or explicitly. Returns NL if there is no explicit nor implicit
     * lock.
     *
     * Since an intent lock does not implicitly grant lock-acquiring privileges to lower levels,
     * if a transaction only has SIX(database), tableContext.getEffectiveLockType(transaction)
     * should return S (not SIX), since the transaction implicitly has S on table via the SIX lock,
     * but not the IX part of the SIX lock (which is only available at the database level).
     * It is possible for the explicit lock type to be one type, and the effective lock type to be a
     * different lock type, specifically if an ancestor has a SIX lock.
     *
     *  A trivial example is db(SIX) -> Table(NL), getEffectiveLockType(Table) = S.
     *
     *  SIX -> IX -> X
     *  The effective locktypes should be SIX, SIX and X. This is because we have an SIX explicitly
     *  on the top lock so this is it's effective locktype. An S lock propagates down to its descendants
     *  so the middle node would implicitly have an S lock but since it explicitly has an IX lock,
     *  it's effective locktype would be SIX (this is somewhat of a special case).
     *
     *  Database(IX) -> Table(X) --> Page(NL)
     *  IX, X, X
     *
     *  Database(IS) -> Table(S) --> Page(NL)
     *  IS, S, S
     *
     *  Database(SIX) -> Table(X) --> Page(NL)
     *  SIX, X, X
     *
     *  Database(SIX) -> Table(NL) --> Page(NL)
     *  SIX, S, S
     *
     *  Database(IX) -> Table(SIX) --> Page(X) --> Tuple(NL)
     *  IX, SIX, X, X
     *
     *  Database(IX) -> Table(SIX) --> Page(IX) --> Tuple(X)
     *  IX, SIX, SIX, X
     *
     *  Database(IS) -> Table(IS) --> Page(S) --> Tuple(NL)
     *  IS, IS, S, S
     *
     *  Database(IX) -> Table(IS) --> Page(IS) --> Tuple(S)
     *  IX, IS, IS, S.
     *  An IS lock with an IX lock as its parent does not imply that the IS lock can grant X locks on its children.
     *
     *  Database(SIX) -> Table(IX) --> Page(IX) --> Tuple(X)
     *  SIX, SIX, SIX, X
     *
     *  SuperTable(IX) -> Database(SIX) -> Table(IX) --> Page(IX) --> Tuple(X)
     *  IX, SIX, SIX, SIX, X
     *
     *
     *
     * ****************************************************************************
     When current lock type is NL, we trace all the way up.

            If there's X lock in the ancestor, the output is X.

            If there's no X lock, but S / SIX lock(s) in the ancestors, then output S.

            If there's only IS/IX/NL lock(s) in the ancestors, then output NL.

     When current lock type is not NL,

            if the current lock type is not IX, then its effective lock type is the same as its explicit lock type, whatever ancestors they have.

            If the current lock type is IX, then if there's SIX lock in its ancestors, the output is SIX. If there's no SIX lock in its ancestors, the output is IX, it remains itself.
     *********************************************************************************
     * EffectiveType is mainly used to determine the equivalent of current type,
     * when it comes to the sufficiency of current type.
     *
     * Only works well when current type is NL, but we already have some useful locks in ancestors so NL is not meaningless
     * And special case: when SIX---IX, if we need S on IX lock now, we don't have to change
     * cuz SIX could help us read the whole database already
     */
    public LockType getEffectiveLockType(TransactionContext transaction) {
        if (transaction == null) return LockType.NL;
        // TODO(proj4_part2): implement
        if (getExplicitLockType(transaction) == LockType.NL) {
            if (hasXAncestor(transaction)) {
                return LockType.X;
            } else if (hasSAncestor(transaction) || hasSIXAncestor(transaction)){
                return LockType.S;
            } else {
                return LockType.NL;
            }
        } else {
            if (getExplicitLockType(transaction) != LockType.IX) {
                return getExplicitLockType(transaction);
            } else {
                if (hasSIXAncestor(transaction)) {
                    return LockType.SIX;
                } else {
                    return LockType.IX;
                }
            }
        }
    }

    /**
     * Helper method to see if the transaction holds a SIX lock at an ancestor
     * of this context
     * @param transaction the transaction
     * @return true if holds a SIX at an ancestor, false if not
     */
    private boolean hasSIXAncestor(TransactionContext transaction) {
        // TODO(proj4_part2): implement
        LockContext thisContext = this.parentContext();
        while(thisContext != null){
            if (lockman.getLockType(transaction, thisContext.getResourceName()) == LockType.SIX) return true;
            else thisContext = thisContext.parentContext();
        }
        return false;
    }

    /**
     * Helper method to see if the transaction holds a X lock at an ancestor
     * of this context
     * @param transaction the transaction
     * @return true if holds a X at an ancestor, false if not
     */
    private boolean hasXAncestor(TransactionContext transaction) {
        LockContext thisContext = this.parentContext();
        while(thisContext != null){
            if (lockman.getLockType(transaction, thisContext.getResourceName()) == LockType.X) return true;
            else thisContext = thisContext.parentContext();
        }
        return false;
    }

    /**
     * Helper method to see if the transaction holds a S lock at an ancestor
     * of this context
     * @param transaction the transaction
     * @return true if holds a S at an ancestor, false if not
     */
    private boolean hasSAncestor(TransactionContext transaction) {
        LockContext thisContext = this.parentContext();
        while(thisContext != null){
            if (lockman.getLockType(transaction, thisContext.getResourceName()) == LockType.S) return true;
            else thisContext = thisContext.parentContext();
        }
        return false;
    }

    /**
     * Helper method to get a list of resourceNames of all locks that are S or
     * IS and are descendants of current context for the given transaction.
     * @param transaction the given transaction
     * @return a list of ResourceNames of descendants which the transaction
     * holds an S or IS lock.
     */
    private List<ResourceName> sisDescendants(TransactionContext transaction) {
        // TODO(proj4_part2): implement
        List<ResourceName> res = new ArrayList<>();
        List<Lock> locks = lockman.getLocks(transaction); // all locks in this transaction
        for (Lock l : locks) { // for each lock
            if (l.lockType == LockType.S ||
                    l.lockType == LockType.IS) {
                if (hasAncestor(fromResourceName(lockman, l.name), this)) {
                    // if this lock in transaction is descendants of this context
                    res.add(l.name);
                }
            }
        }
        return res;
    }

    /**
     *  return if this lock context has 'ancestor' as an ancestor
     *
     * @param context a random lock context we got from lockmanger layer
     * @param ancestor to determine if this lock context is some ancestor of the 'context'
     * @return true if this has ancestor as an ancestor or if the ancestor is null, else false.
     *  if they are same, then return false cuz it can not be itself's ancestor.
     */
    private boolean hasAncestor(LockContext context, LockContext ancestor) {
        if (context == null) { return false; }
        if (ancestor == null) { return true; }
        if (context == ancestor) {return false; }
        LockContext travel = context;
        while (travel != null) {
            if (travel.equals(ancestor)) { return true; }
            travel = travel.parent;
        }
        return false;
    }

    /**
     * Disables locking descendants. This causes all new child contexts of this
     * context to be readonly. This is used for indices and temporary tables
     * (where we disallow finer-grain locks), the former due to complexity
     * locking B+ trees, and the latter due to the fact that temporary tables
     * are only accessible to one transaction, so finer-grain locks make no
     * sense.
     */
    public void disableChildLocks() {
        this.childLocksDisabled = true;
    }

    /**
     * Gets the parent context.
     */
    public LockContext parentContext() {
        return parent;
    }

    /**
     * Gets the context for the child with name `name` and readable name
     * `readable`
     */
    public synchronized LockContext childContext(String name) {
        LockContext temp = new LockContext(lockman, this, name,
                this.childLocksDisabled || this.readonly);
        LockContext child = this.children.putIfAbsent(name, temp);
        if (child == null) child = temp;
        return child;
    }

    /**
     * Gets the context for the child with name `name`.
     */
    public synchronized LockContext childContext(long name) {
        return childContext(Long.toString(name));
    }

    /**
     * Gets the number of locks held on children a single transaction.
     */
    public int getNumChildren(TransactionContext transaction) {
        return numChildLocks.getOrDefault(transaction.getTransNum(), 0);
    }

    @Override
    public String toString() {
        return "LockContext(" + name.toString() + ")";
    }
}

