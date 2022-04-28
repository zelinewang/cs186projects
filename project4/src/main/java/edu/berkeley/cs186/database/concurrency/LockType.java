package edu.berkeley.cs186.database.concurrency;

/**
 * Utility methods to track the relationships between different lock types.
 */
public enum LockType {
    S,   // shared
    X,   // exclusive
    IS,  // intention shared
    IX,  // intention exclusive
    SIX, // shared intention exclusive
    NL;  // no lock held

    /**
     * This method checks whether lock types A and B are compatible with
     * each other. If a transaction can hold lock type A on a resource
     * at the same time another transaction holds lock type B on the same
     * resource, the lock types are compatible.
     */
    public static boolean compatible(LockType a, LockType b) {
        if (a == null || b == null) {
            throw new NullPointerException("null lock type");
        }
        if (a == NL || b == NL) {
            return true;
        }
        if (a == X || b == X) {
            if ((a == X && b == NL) || (a == NL && b == X)) {
                return true;
            }
            return false;
        }
        if (a == IS || b == IS) {
            return true;
        } else if (a == SIX || b == SIX) {
            return false;
        }
        if (a == IX && b == IX) {
            return true;
        }
        if (a == S && b == S) {
            return true;
        }
        // TODO(proj4_part1): implement
        // NL should be compatible with every lock type
        // S is compatible with S, and IS
        // S is incompatible with X, IX, and SIX
        // Intent locks are compatible with each other
        // X locks are incompatible with X locks
        return false;
    }

    /**
     * This method returns the lock on the parent resource
     * that should be requested for a lock of type A to be granted.
     */
    public static LockType parentLock(LockType a) {
        if (a == null) {
            throw new NullPointerException("null lock type");
        }
        switch (a) {
        case S: return IS;
        case X: return IX;
        case IS: return IS;
        case IX: return IX;
        case SIX: return IX;
        case NL: return NL;
        default: throw new UnsupportedOperationException("bad lock type");
        }
    }

    /**
     * This method returns if parentLockType has permissions to grant a childLockType
     * on a child.
     *
     * parentLock returns the least restrictive lock type the parent resource should be for a lock of type A to be granted.
     * However, the least restrictive lock type is not necessarily the only lock type that can grant lock type A.
     * For example, IX locks can also acquire S locks at a finer granularity.
     * The purpose of canBeParentLock is to support these cases as well.
     */
    public static boolean canBeParentLock(LockType parentLockType, LockType childLockType) {
        if (parentLockType == null || childLockType == null) {
            throw new NullPointerException("null lock type");
        }
        switch(parentLockType) {
            case S:
                if (childLockType == NL) return true;
                break;
            case X:
                if (childLockType == NL) return true;
                break;
            case IS:
                if (childLockType == IS || childLockType == S || childLockType == NL) return true;
                break;
            case IX:
                return true;
            case SIX:
                if (childLockType == X || childLockType == IX || childLockType == NL) return true;
                break;
            case NL:
                if (childLockType == NL) return true;
                break;
        }
        return false;
        // TODO(proj4_part1): implement
        // Any lock type can be parent of NL
        // The only lock type that can be a child of NL is NL
        // IX can be the parent of any type of lock
        // IS can be the parent of IS, S, and NL
        // IS cannot be the parent of IX, X, or SIX
    }

    /**
     * This method returns whether a lock can be used for a situation
     * requiring another lock (e.g. an S lock can be substituted with
     * an X lock, because an X lock allows the transaction to do everything
     * the S lock allowed it to do).
     */
    public static boolean substitutable(LockType substitute, LockType required) {
        if (required == null || substitute == null) {
            throw new NullPointerException("null lock type");
        }
        if (required == NL) return true;
        // If a transaction has no lock on a resource then it can't do anything with
        // that resource or children of that resource. Having any other lock type would
        // expand the set of things the transaction can do, so any other lock type can substitute NL
        if (required == SIX && substitute == SIX) return true;
        // Substituting SIX with IX would take away T1's ability to read R and read children of R,
        // so IX is not a valid substitute.

        if (required == IS && (substitute == IX || substitute == IS)) return true;
        // Substituting IS with SIX would take away T1's ability request S, or IS locks on children of R.
        // Although this might seem okay, substituting an IS while it still has IS, S children would lead to redundant locks.
        // So, SIX is not a valid substitute.

        if (required == IX && substitute == IX) return true;
        // Substituting IX with SIX would take away T1's ability request S, IS, or SIX locks on children of R.
        // Although this might seem okay, substituting an IX while it still has IS, S, or SIX children would lead to redundan locks.
        // So, SIX is not a valid substitute.

        if (required == S) {
            if (substitute == X || substitute == S || substitute == SIX) return true;
        }
        if (required == X && substitute == X) return true;
        // You cannot substitute S with IS or IX
        // You can substitute S with S, SIX, or X
        // You cannot substitute X with IS, IX, S, or SIX
        // You can substitute X with X
        // You can substitute intent locks with themselves
        // IX's privileges are a superset of IS's privileges
        // IS's privileges are not a superset of IX's privileges
        // You can't substitute anything with NL, other than NL
        // TODO(proj4_part1): implement

        return false;
    }

    /**
     * @return True if this lock is IX, IS, or SIX. False otherwise.
     */
    public boolean isIntent() {
        return this == LockType.IX || this == LockType.IS || this == LockType.SIX;
    }

    @Override
    public String toString() {
        switch (this) {
        case S: return "S";
        case X: return "X";
        case IS: return "IS";
        case IX: return "IX";
        case SIX: return "SIX";
        case NL: return "NL";
        default: throw new UnsupportedOperationException("bad lock type");
        }
    }
}

