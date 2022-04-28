package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;

/**
 * LockUtil is a declarative layer which simplifies multigranularity lock
 * acquisition for the user (you, in the last task of Part 2). Generally
 * speaking, you should use LockUtil for lock acquisition instead of calling
 * LockContext methods directly.
 */
public class LockUtil {
    /**
     * This method is used like a declarative statement.
     *
     * We suggest breaking up the logic of this method into two phases:
     * ensuring that we have the appropriate locks on ancestors,
     * and acquiring the lock on the resource.
     *
     * You will need to promote in some cases,
     * and escalate in some cases (these cases are not mutually exclusive).
     *
     * Ensure that the current transaction can perform actions requiring
     * `requestType` on `lockContext`.
     *
     * `requestType` is guaranteed to be one of: S, X, NL.
     *
     * This method should promote/escalate/acquire as needed, but should only
     * grant the least permissive set of locks needed. We recommend that you
     * think about what to do in each of the following cases:
     * - The current lock type can effectively substitute the requested type --- does nothing
     * - The current lock type is IX and the requested lock is S --- promote it to SIX
     * - The current lock type is an intent lock --- consider escalate first
     * - None of the above (current type is S/X/NL): In this case, consider what values the explicit
     *   lock type can be, and think about how ancestor looks will need to be
     *   acquired or changed.
     *
     * If the requestType is the same, the function does not error, and nothing changes,
     * since the lock can already be held.
     *
     * when the current lock type is IX and the requested lock is S, we should consider promote the current lock
     * when the current lock type is an intent lock, we should consider escalate
     *
     * For SIX
     *  - We cannot acquire/promote any lock to S/IS/SIX lock if we have a SIX ancestor.
     * Reason: canBeParent(SIX, S/IS/SIX) == false. Invalid request.
     * Case: SIX(db) -> NL(table). We cannot acquire S/IS/SIX on table.
     *  - We can promote a IS/IX/S lock to SIX, but we need to release all descendant S/IS.
     * Reason: special case even though substitutable(SIX, IS/IX) == false. Because in this case, we are not using the promote() method
     * but the acquireAndRelease() method.
     * Case 1: IS(db) -> S(table) ==> SIX(db) -> NL(table).
     * !!Note!!: This will allow a SIX lock to be the ancestor of another SIX lock.
     * Case 2: IX(db) -> SIX(table) ==> SIX(db) -> SIX(table).
     *
     * 1. If a lock is now IS and it needs to be X, have to first escalate to get rid of descendant IS/S locks.
     * After the escalation, your current lock goes becomes an S lock. You can then promote that lock to be an X lock
     * (assuming your ancestors have the proper locks to allow that).
     *
     * 2. if we have IX -> SIX -> X and we request S on the IX lock
     * This does still allow for SIX locks to be held under a SIX lock,
     * in the case of promoting an ancestor to SIX while a descendant holds SIX.
     *
     * 3. SIX - IX - X
     * If we try to request an S lock on the table with the above structure, we do nothing.
     * A SIX lock on the database grants read/S permissions to all its descendants so the table already effectively has read permissions.
     *
     * 3. if parent's emplicityimplicit is SIX, S, OR X, and current type is NL, we don't need to do anything
     * cuz the
     *
     * You may find it useful to create a helper method that ensures you have
     * the appropriate locks on all ancestors. If there are not appropriate locks on the ancestors, they have to be updated.
     * NOTE: we would only have requestType as S/X/NL, and Requesting NL should do nothing.
     *
     * *************************************
     * EnsureParents ensures ancestors to be good
     * Escalate() ensures descendants to be good
     * Promote() is for some special changing case, of course escalate() also changes IS/IX/SIX to S/X
     */
    public static void ensureSufficientLockHeld(LockContext lockContext, LockType requestType) {
        // requestType must be S, X, or NL
        assert (requestType == LockType.S || requestType == LockType.X || requestType == LockType.NL);

        // Do nothing if the transaction or lockContext is null
        TransactionContext transaction = TransactionContext.getTransaction();
        if (transaction == null || lockContext == null) return;

        // You may find these variables useful
        LockContext parentContext = lockContext.parentContext();
        LockType effectiveLockType = lockContext.getEffectiveLockType(transaction);
        LockType explicitLockType = lockContext.getExplicitLockType(transaction);

        // TODO(proj4_part2): implement

        if (transaction == null || requestType.equals(LockType.NL)) {
            return;
        } // only consider request S and X
        if (LockType.substitutable(effectiveLockType, requestType)) {
            return;
            // means what we had could already make it to do what we want to in this request.
            // Don't need to change anything
            // including SIX-IX-X and request S on IX case, cuz SIX could substitute S.
        }



        if (effectiveLockType == LockType.IX && requestType == LockType.S) {
            // when it doesn't have any SIX in ancestors (getEffectiveType), IX-IX-X
            // ensure parents
            ensureParent(transaction, parentContext, LockType.SIX);
            lockContext.promote(transaction, LockType.SIX);
            // use Acquire and Release in this promote of Context, cuz Promote was only used when New could substitute Old,
            // but now we want SIX replace IX, which can't work in Promote()
            // So we use acquireAndRelease() in this special case!!!!!!!!!!!
        } else if (effectiveLockType == LockType.IX && requestType == LockType.X) {
            // if current is IX, then there is no SIX ancestor. and We request X.
            // ensure parents
            ensureParent(transaction, parentContext, LockType.X);
            lockContext.escalate(transaction); // IX became X.
        }



        // The current lock type is an intent lock
        if (effectiveLockType == LockType.IS) { // explicitType is also IS
            // first escalate
            ensureParent(transaction, parentContext, LockType.S);
            lockContext.escalate(transaction);// make it to S
            if (requestType == LockType.S) {
                return;
            } else { //request X on IS
                //ensure parents!!!!
                ensureParent(transaction, parentContext, LockType.X);
                lockContext.promote(transaction, LockType.X);
                //      * 1. If a lock is now IS and it needs to be X, have to first escalate to get rid of descendant IS/S locks.
                //     * After the escalation, your current lock goes becomes an S lock. You can then promote that lock to be an X lock
                //     * (assuming your ancestors have the proper locks to allow that).
            }
        }
        if (effectiveLockType == LockType.SIX) {
            // we already considered when request is S, so could only be X, cuz SIX could substitute S
            // request X, whether explicit is IX or SIX, we escalate it to X
            ensureParent(transaction, parentContext, LockType.X);
            lockContext.escalate(transaction); // became X.
        }



        // None of the above (current type is S/X/NL)
        if (effectiveLockType == LockType.S) {
            // explicit value could be NL(has S/SIX ancestor) and S
            // if request S, does nothing, since substitute()
            // only consider request X,

//            ensureParent(transaction, parentContext, LockType.X);
//            updateType(transaction, lockContext, LockType.X);
            if (explicitLockType == LockType.S) {
                ensureParent(transaction, parentContext, LockType.X);
                lockContext.promote(transaction, LockType.X);
            }
            if (explicitLockType == LockType.NL) {
                ensureParent(transaction, parentContext, LockType.X);
                lockContext.acquire(transaction, LockType.X);
            }
        }

        if (effectiveLockType == LockType.X) {
            // explicit value could be NL(has X ancestor) and X
            // if request S, does nothing
            // only consider request X, but if explicit is X, does nothing
            // only consider request X on NL
            ensureParent(transaction, parentContext, LockType.X);
            lockContext.acquire(transaction, LockType.X);
        }

        if (effectiveLockType == LockType.NL) {
            // When there's only IS/IX/NL lock(s) in the ancestors
            // the explicit could only be NL. cuz if IX or IS, the effective type won't be NL too.
            // And the tree could only be IX-IX-----IS-IS-----NL-NL, or IX-----NL(no IS) or IS----NL(no IX)
            // And these could be possible, if IX on table, X on page 1 but NL on page 2, this happens.
            if (requestType == LockType.S) {
                // when request S.
                ensureParent(transaction, parentContext, LockType.S);
                lockContext.acquire(transaction, LockType.S);
            } else if (requestType == LockType.X) {
                ensureParent(transaction, parentContext, LockType.X);
                lockContext.acquire(transaction, LockType.X);
            }
        }
    }

        /**
         * ensure the parent type of current requestedType
         *
         * If we have NL-NL- S(database-table-page)
         * we want to ensure the ancestors of requested S type on Page ( turning into NL-NL-'S')
         * We could call ensureParent(transaction, TableContext, S)
         * TableContext here is the parentContext.
         * */
        private static void ensureParent(TransactionContext transaction,
                                             LockContext parentContext, LockType requestedType){
            // ensure this parent of our this requestType Context to be sufficient for this requested type
            // RequestedType could be SIX/IS/IX/X/S. should not be NL
            if (parentContext == null) {
                return;
            }
            if (requestedType == LockType.NL) {
                return; // everything in this parent could ensure NL
            }
            LockType parentType = parentContext.getExplicitLockType(transaction);
            if (requestedType == LockType.SIX) {
                if (parentType != LockType.IX && parentType != LockType.SIX) {
                    ensureParent(transaction, parentContext.parentContext(), LockType.parentLock(LockType.SIX));
                    updateType(transaction, parentContext, LockType.parentLock(LockType.SIX));
                    return;
                }
            }
            if (requestedType == LockType.S) {
                if (parentType != LockType.IS && parentType != LockType.IX) {
                    ensureParent(transaction, parentContext.parentContext(), LockType.parentLock(LockType.IS));
                    updateType(transaction, parentContext, LockType.parentLock(LockType.IS));
                    return;
                }
            }
            if (requestedType == LockType.X) {
                if (parentType != LockType.IX && parentType != LockType.SIX) {
                    ensureParent(transaction, parentContext.parentContext(), LockType.parentLock(LockType.IX));
                    updateType(transaction, parentContext, LockType.parentLock(LockType.IX));
                    return;
                }
            }
            if (requestedType == LockType.IS) {
                if (parentType != LockType.IX && parentType != LockType.IS) {
                    ensureParent(transaction, parentContext.parentContext(), LockType.parentLock(LockType.IS));
                    updateType(transaction, parentContext, LockType.parentLock(LockType.IS));
                    return;
                }
            }
            if (requestedType == LockType.IX) {
                if (parentType != LockType.IX && parentType != LockType.SIX) {
                    ensureParent(transaction, parentContext.parentContext(), LockType.parentLock(LockType.IX));
                    updateType(transaction, parentContext, LockType.parentLock(LockType.IX));
                    return;
                }
            }
        }

        /**
         * If context is null, return
         * If context has no locks, acquire a new lock
         * If context has a lock, update it to new lockType, if these two are same, do nothing
         * */
        private static void updateType(TransactionContext transaction, LockContext context, LockType updateType) {
            if (context == null) {
                return;
            }
            if (context.getExplicitLockType(transaction) == LockType.NL) {
                context.acquire(transaction, updateType);
            } else {
                if (context.getExplicitLockType(transaction) == updateType) {
                    return;
                } else {
                    context.promote(transaction, updateType);
                }
            }
        }

}
