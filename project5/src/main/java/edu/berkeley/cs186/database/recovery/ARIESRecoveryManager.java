package edu.berkeley.cs186.database.recovery;

import edu.berkeley.cs186.database.Transaction;
import edu.berkeley.cs186.database.TransactionContext;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.concurrency.LockContext;
import edu.berkeley.cs186.database.concurrency.LockType;
import edu.berkeley.cs186.database.concurrency.LockUtil;
import edu.berkeley.cs186.database.concurrency.DummyLockContext;
import edu.berkeley.cs186.database.io.DiskSpaceManager;
import edu.berkeley.cs186.database.memory.BufferManager;
import edu.berkeley.cs186.database.memory.Page;
import edu.berkeley.cs186.database.recovery.records.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Implementation of ARIES.
 */
public class ARIESRecoveryManager implements RecoveryManager {
    // Disk space manager.
    DiskSpaceManager diskSpaceManager;
    // Buffer manager.
    BufferManager bufferManager;

    // Function to create a new transaction for recovery with a given
    // transaction number.
    private Function<Long, Transaction> newTransaction;

    // Log manager
    LogManager logManager;
    // Dirty page table (page number -> recLSN).
    Map<Long, Long> dirtyPageTable = new ConcurrentHashMap<>();
    // Transaction table (transaction number -> entry).
    Map<Long, TransactionTableEntry> transactionTable = new ConcurrentHashMap<>();
    // true if redo phase of restart has terminated, false otherwise. Used
    // to prevent DPT entries from being flushed during restartRedo.
    boolean redoComplete;

    // List of lock requests made during recovery. This is only populated when locking is disabled.
    List<String> lockRequests;

    public ARIESRecoveryManager(Function<Long, Transaction> newTransaction) {
        this.newTransaction = newTransaction;
    }

    /**
     * Initializes the log; only called the first time the database is set up.
     * The master record should be added to the log, and a checkpoint should be
     * taken.
     */
    @Override
    public void initialize() {
        this.logManager.appendToLog(new MasterLogRecord(0));
        this.checkpoint();
    }

    /**
     * Sets the buffer/disk managers. This is not part of the constructor
     * because of the cyclic dependency between the buffer manager and recovery
     * manager (the buffer manager must interface with the recovery manager to
     * block page evictions until the log has been flushed, but the recovery
     * manager needs to interface with the buffer manager to write the log and
     * redo changes).
     * @param diskSpaceManager disk space manager
     * @param bufferManager buffer manager
     */
    @Override
    public void setManagers(DiskSpaceManager diskSpaceManager, BufferManager bufferManager) {
        this.diskSpaceManager = diskSpaceManager;
        this.bufferManager = bufferManager;
        this.logManager = new LogManager(bufferManager);
    }

    // Forward Processing //////////////////////////////////////////////////////

    /**
     * Called when a new transaction is started.
     *
     * The transaction should be added to the transaction table.
     *
     * @param transaction new transaction
     */
    @Override
    public synchronized void startTransaction(Transaction transaction) {
        this.transactionTable.put(transaction.getTransNum(), new TransactionTableEntry(transaction));
    }

    /**
     * Called when a transaction is about to start committing.
     *
     * A commit record should be appended, the log should be flushed,
     * and the transaction table and the transaction status should be updated.
     *
     * @param transNum transaction being committed
     * @return LSN of the commit record
     */
    @Override
    public long commit(long transNum) {
        // TODO(proj5): implement
        TransactionTableEntry entry = this.transactionTable.get(transNum);
        long currentLSN = entry.lastLSN;
        LogRecord newRecord = new CommitTransactionLogRecord(transNum, currentLSN);

        long newLSN = logManager.appendToLog(newRecord);
        entry.lastLSN = newLSN;
        this.pageFlushHook(newLSN);

        entry.transaction.setStatus(Transaction.Status.COMMITTING);

        return newLSN;
    }

    /**
     * Called when a transaction is set to be aborted.
     *
     * An abort record should be appended, and the transaction table and
     * transaction status should be updated. Calling this function should not
     * perform any rollbacks.
     *
     * @param transNum transaction being aborted
     * @return LSN of the abort record
     */
    @Override
    public long abort(long transNum) {
        // TODO(proj5): implement
        TransactionTableEntry entry = this.transactionTable.get(transNum);
        long currentLSN = entry.lastLSN;
        long newLSN = logManager.appendToLog(new AbortTransactionLogRecord(transNum, currentLSN));
        entry.transaction.setStatus(Transaction.Status.ABORTING);
        entry.lastLSN = newLSN;
        return newLSN;
    }

    /**
     * Called when a transaction is cleaning up; this should roll back
     * changes if the transaction is aborting (see the rollbackToLSN helper
     * function below).
     *
     * Any changes that need to be undone should be undone, the transaction should
     * be removed from the transaction table, the end record should be appended,
     * and the transaction status should be updated.
     *
     * @param transNum transaction to end
     * @return LSN of the end record
     */
    @Override
    public long end(long transNum) {
        // TODO(proj5): implement
        TransactionTableEntry entry = this.transactionTable.get(transNum);
        long LSN = entry.lastLSN;
        if(entry.transaction.getStatus() == Transaction.Status.ABORTING) {
            rollbackToLSN(transNum, entry.lastLSN);
        }
        long newLSN = logManager.appendToLog(new EndTransactionLogRecord(transNum, entry.lastLSN));
        entry.transaction.setStatus(Transaction.Status.COMPLETE);
        entry.lastLSN = newLSN;
        transactionTable.remove(transNum);
        return newLSN;
    }

    /**
     * Recommended helper function: performs a rollback of all of a
     * transaction's actions, up to (but not including) a certain LSN.
     * Starting with the LSN of the most recent record that hasn't been undone:
     * - while the current LSN is greater than the LSN we're rolling back to:
     *    - if the record at the current LSN is undoable:
     *       - Get a compensation log record (CLR) by calling undo on the record
     *       - Append the CLR
     *       - Call redo on the CLR to perform the undo
     *    - update the current LSN to that of the next record to undo
     *
     * Note above that calling .undo() on a record does not perform the undo, it
     * just creates the compensation log record.
     *
     * @param transNum transaction to perform a rollback for
     * @param LSN LSN to which we should rollback
     */
    private void rollbackToLSN(long transNum, long LSN) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        // TODO(proj5) implement the rollback logic described above
        long nextLSN = LSN;
        LogRecord r = logManager.fetchLogRecord(LSN);
        while (!r.getPrevLSN().equals(Optional.empty())) {
            if (r.isUndoable()) {
                LogRecord clr = r.undo(nextLSN);
                nextLSN = logManager.appendToLog(clr);
                transactionEntry.lastLSN = nextLSN;

                if(!(clr instanceof UndoAllocPartLogRecord) && !(clr instanceof UndoFreePartLogRecord)){
                    if(!dirtyPageTable.containsKey(clr.getPageNum().get())){
                        dirtyPageTable.put(clr.getPageNum().get(), nextLSN);
                    }
                }
//                if(needflush){
//                    this.pageFlushHook(nextLSN);
//                    if(!(clr instanceof UndoAllocPartLogRecord) && !(clr instanceof UndoFreePartLogRecord)){
//                        this.diskIOHook(clr.getPageNum().get());
//                    }
//                }
                clr.redo(this, diskSpaceManager, bufferManager);
            }
            r = logManager.fetchLogRecord(r.getPrevLSN().get());
        }
    }

    /**
     * Called before a page is flushed from the buffer cache. This
     * method is never called on a log page.
     *
     * The log should be as far as necessary.
     *
     * @param pageLSN pageLSN of page about to be flushed
     */
    @Override
    public void pageFlushHook(long pageLSN) {
        logManager.flushToLSN(pageLSN);
    }

    /**
     * Called when a page has been updated on disk.
     *
     * As the page is no longer dirty, it should be removed from the
     * dirty page table.
     *
     * @param pageNum page number of page updated on disk
     */
    @Override
    public void diskIOHook(long pageNum) {
        if (redoComplete) dirtyPageTable.remove(pageNum);
    }

    /**
     * Called when a write to a page happens.
     *
     * This method is never called on a log page. Arguments to the before and after params
     * are guaranteed to be the same length.
     *
     * The appropriate log record should be appended, and the transaction table
     * and dirty page table should be updated accordingly.
     *
     * @param transNum transaction performing the write
     * @param pageNum page number of page being written
     * @param pageOffset offset into page where write begins
     * @param before bytes starting at pageOffset before the write
     * @param after bytes starting at pageOffset after the write
     * @return LSN of last record written to log
     */
    @Override
    public long logPageWrite(long transNum, long pageNum, short pageOffset, byte[] before,
                             byte[] after) {
        assert (before.length == after.length);
        assert (before.length <= BufferManager.EFFECTIVE_PAGE_SIZE / 2);
        // TODO(proj5): implement
        TransactionTableEntry entry = this.transactionTable.get(transNum);
        Transaction transaction = entry.transaction;
        long LSN = entry.lastLSN;
        int pageSize = before.length;
        if(pageSize <= BufferManager.EFFECTIVE_PAGE_SIZE / 2){
            Long LSNfirst = logManager.appendToLog(new UpdatePageLogRecord(transNum, pageNum, LSN, pageOffset, before, after));
//            entry.touchedPages.add(pageNum);
            if(!dirtyPageTable.containsKey(pageNum))
                dirtyPageTable.put(pageNum, LSNfirst);
            entry.lastLSN = LSNfirst;
            return LSNfirst;
        }else{
            Long LSNfirst = logManager.appendToLog(new UpdatePageLogRecord(transNum, pageNum, LSN, pageOffset, before, new byte[0]));
            Long LSNsec = logManager.appendToLog(new UpdatePageLogRecord(transNum, pageNum, LSNfirst, pageOffset, new byte[0], after));
//            entry.touchedPages.add(pageNum);
            if(!dirtyPageTable.containsKey(pageNum))
                dirtyPageTable.put(pageNum, LSNfirst);
            entry.lastLSN = LSNsec;
            return LSNsec;
        }
    }

    /**
     * Called when a new partition is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param partNum partition number of the new partition
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) return -1L;
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a partition is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the partition be freed
     * @param partNum partition number of the partition being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a new page is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the page is in the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param pageNum page number of the new page
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a page is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the page is in the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the page be freed
     * @param pageNum page number of the page being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        dirtyPageTable.remove(pageNum);
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Creates a savepoint for a transaction. Creating a savepoint with
     * the same name as an existing savepoint for the transaction should
     * delete the old savepoint.
     *
     * The appropriate LSN should be recorded so that a partial rollback
     * is possible later.
     *
     * @param transNum transaction to make savepoint for
     * @param name name of savepoint
     */
    @Override
    public void savepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);
        transactionEntry.addSavepoint(name);
    }

    /**
     * Releases (deletes) a savepoint for a transaction.
     * @param transNum transaction to delete savepoint for
     * @param name name of savepoint
     */
    @Override
    public void releaseSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);
        transactionEntry.deleteSavepoint(name);
    }

    /**
     * Rolls back transaction to a savepoint.
     *
     * All changes done by the transaction since the savepoint should be undone,
     * in reverse order, with the appropriate CLRs written to log. The transaction
     * status should remain unchanged.
     *
     * @param transNum transaction to partially rollback
     * @param name name of savepoint
     */
    @Override
    public void rollbackToSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        // All of the transaction's changes strictly after the record at LSN should be undone.
        long savepointLSN = transactionEntry.getSavepoint(name);

        // TODO(proj5): implement
        TransactionTableEntry entry = this.transactionTable.get(transNum);
        Transaction transaction = entry.transaction;
        long LSN = entry.lastLSN;
        long nextLSN = LSN;

        if(LSN == savepointLSN) return;
        while (true) {
            LogRecord r = logManager.fetchLogRecord(LSN);
            if (r.isUndoable()) {
                LogRecord clr = r.undo(nextLSN);
                nextLSN = logManager.appendToLog(clr);
                entry.lastLSN = nextLSN;
                if(!(clr instanceof UndoAllocPartLogRecord) && !(clr instanceof UndoFreePartLogRecord)){
                    if(!dirtyPageTable.containsKey(clr.getPageNum().get())){
                        dirtyPageTable.put(clr.getPageNum().get(), nextLSN);
                    }
                }
                clr.redo(this, diskSpaceManager, bufferManager);
            }
            if(r.getUndoNextLSN().isPresent() && r.getUndoNextLSN().get() == savepointLSN) break;
            else if(r.getPrevLSN().get() == savepointLSN) break;
            LSN = r.getPrevLSN().get();
        }
    }

    /**
     * Create a checkpoint.
     *
     * First, a begin checkpoint record should be written.
     *
     * Then, end checkpoint records should be filled up as much as possible first
     * using recLSNs from the DPT, then status/lastLSNs from the transactions
     * table, and written when full (or when nothing is left to be written).
     * You may find the method EndCheckpointLogRecord#fitsInOneRecord here to
     * figure out when to write an end checkpoint record.
     *
     * Finally, the master record should be rewritten with the LSN of the
     * begin checkpoint record.
     */
    @Override
    public synchronized void checkpoint() {
        // Create begin checkpoint log record and write to log
        LogRecord beginRecord = new BeginCheckpointLogRecord();
        long beginLSN = logManager.appendToLog(beginRecord);

        Map<Long, Long> chkptDPT = new HashMap<>();
        Map<Long, Pair<Transaction.Status, Long>> chkptTxnTable = new HashMap<>();
        Map<Long, List<Long>> touchedPages = new HashMap<>();
        int numTouchedPages = 0;

        // TODO(proj5): generate end checkpoint record(s) for DPT and transaction table
        for (Map.Entry<Long, Long> entry : dirtyPageTable.entrySet()) {
            boolean fitable = EndCheckpointLogRecord.fitsInOneRecord(
                    chkptDPT.size() + 1, 0);
            if (!fitable) {
                LogRecord endRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
                logManager.appendToLog(endRecord);
                chkptDPT.clear();
            }
            chkptDPT.put(entry.getKey(), entry.getValue());
        }
        // Cpy Xact Table's XactID, Status, LastLSN
        for (Map.Entry<Long, TransactionTableEntry> entry : transactionTable.entrySet()) {
            long transNum = entry.getKey();
            TransactionTableEntry xactEntry = entry.getValue();
            Transaction.Status status = xactEntry.transaction.getStatus();
            long lastLSN = xactEntry.lastLSN;

            boolean fitable = EndCheckpointLogRecord.fitsInOneRecord(
                    chkptDPT.size(), chkptTxnTable.size() + 1);
            if (!fitable) {
                LogRecord endRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
                logManager.appendToLog(endRecord);
                chkptDPT.clear();
                chkptTxnTable.clear();
            }
            chkptTxnTable.put(transNum, new Pair<>(status, lastLSN));
        }

        // Last end checkpoint record
        LogRecord endRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
        logManager.appendToLog(endRecord);
        // Ensure checkpoint is fully flushed before updating the master record
        flushToLSN(endRecord.getLSN());

        // Update master record
        MasterLogRecord masterRecord = new MasterLogRecord(beginLSN);
        logManager.rewriteMasterRecord(masterRecord);
    }

    /**
     * Flushes the log to at least the specified record,
     * essentially flushing up to and including the page
     * that contains the record specified by the LSN.
     *
     * @param LSN LSN up to which the log should be flushed
     */
    @Override
    public void flushToLSN(long LSN) {
        this.logManager.flushToLSN(LSN);
    }

    @Override
    public void dirtyPage(long pageNum, long LSN) {
        dirtyPageTable.putIfAbsent(pageNum, LSN);
        // Handle race condition where earlier log is beaten to the insertion by
        // a later log.
        dirtyPageTable.computeIfPresent(pageNum, (k, v) -> Math.min(LSN,v));
    }

    @Override
    public void close() {
        this.checkpoint();
        this.logManager.close();
    }

    // Restart Recovery ////////////////////////////////////////////////////////

    /**
     * Called whenever the database starts up, and performs restart recovery.
     * Recovery is complete when the Runnable returned is run to termination.
     * New transactions may be started once this method returns.
     *
     * This should perform the three phases of recovery, and also clean the
     * dirty page table of non-dirty pages (pages that aren't dirty in the
     * buffer manager) between redo and undo, and perform a checkpoint after
     * undo.
     */
    @Override
    public void restart() {
        this.restartAnalysis();
        this.restartRedo();
        this.redoComplete = true;
        this.cleanDPT();
        this.restartUndo();
        this.checkpoint();
    }

    /**
     * This method performs the analysis pass of restart recovery.
     *
     * First, the master record should be read (LSN 0). The master record contains
     * one piece of information: the LSN of the last successful checkpoint.
     *
     * We then begin scanning log records, starting at the beginning of the
     * last successful checkpoint.
     *
     * If the log record is for a transaction operation (getTransNum is present)
     * - update the transaction table
     *
     * If the log record is page-related (getPageNum is present), update the dpt
     *   - update/undoupdate page will dirty pages
     *   - free/undoalloc page always flush changes to disk
     *   - no action needed for alloc/undofree page
     *
     * If the log record is for a change in transaction status:
     * - update transaction status to COMMITTING/RECOVERY_ABORTING/COMPLETE
     * - update the transaction table
     * - if END_TRANSACTION: clean up transaction (Transaction#cleanup), remove
     *   from txn table, and add to endedTransactions
     *
     * If the log record is an end_checkpoint record:
     * - Copy all entries of checkpoint DPT (replace existing entries if any)
     * - Skip txn table entries for transactions that have already ended
     * - Add to transaction table if not already present
     * - Update lastLSN to be the larger of the existing entry's (if any) and
     *   the checkpoint's
     * - The status's in the transaction table should be updated if it is possible
     *   to transition from the status in the table to the status in the
     *   checkpoint. For example, running -> aborting is a possible transition,
     *   but aborting -> running is not.
     *
     * After all records in the log are processed, for each ttable entry:
     *  - if COMMITTING: clean up the transaction, change status to COMPLETE,
     *    remove from the ttable, and append an end record
     *  - if RUNNING: change status to RECOVERY_ABORTING, and append an abort
     *    record
     *  - if RECOVERY_ABORTING: no action needed
     */
    void restartAnalysis() {
        // Read master record
        LogRecord record = logManager.fetchLogRecord(0L);
        // Type checking
        assert (record != null && record.getType() == LogType.MASTER);
        MasterLogRecord masterRecord = (MasterLogRecord) record;
        // Get start checkpoint LSN
        long LSN = masterRecord.lastCheckpointLSN;
        // Set of transactions that have completed
        Set<Long> endedTransactions = new HashSet<>();
        // TODO(proj5): implement
        Iterator<LogRecord> iter = logManager.scanFrom(LSN);
        while (iter.hasNext()) {
            LogRecord logRec = iter.next();
            LogType recordType = logRec.getType();
            if (isPageType(logRec) || isPartitionType(logRec)) {
                if (!logRec.getTransNum().isPresent()) {
                    continue;
                }

                long transNum = logRec.getTransNum().get();
                TransactionTableEntry tableEntry = transactionTable.get(transNum);
                tableEntry = createNewXact(tableEntry, transNum);

                tableEntry.lastLSN = logRec.getLSN();

                if (isPartitionType(logRec)) {
                    continue;
                }

                if (!logRec.getPageNum().isPresent()) {
                    continue;
                }

                long pageNum = logRec.getPageNum().get();
                if (recordType == LogType.UPDATE_PAGE || recordType == LogType.UNDO_UPDATE_PAGE) {
                    dirtyPageTable.putIfAbsent(pageNum, logRec.getLSN());
                } else {
                    dirtyPageTable.remove(pageNum);
                }
            }


            if (recordType == LogType.ABORT_TRANSACTION
                    || recordType == LogType.COMMIT_TRANSACTION
                    || recordType == LogType.END_TRANSACTION) {
                if (!logRec.getTransNum().isPresent()) {
                    continue;
                }
                if (!logRec.getTransNum().isPresent()) {
                    continue;
                }
                long recordTransNum = logRec.getTransNum().get();
                TransactionTableEntry recordTableEntry = transactionTable.get(recordTransNum);
                recordTableEntry = createNewXact(recordTableEntry, recordTransNum);

                Transaction xact = recordTableEntry.transaction;
                recordTableEntry.lastLSN = Math.max(logRec.getLSN(), recordTableEntry.lastLSN);

                if (recordType == LogType.END_TRANSACTION) {
                    xact.cleanup();
                    xact.setStatus(Transaction.Status.COMPLETE);
                    transactionTable.remove(xact.getTransNum());
                } else if (recordType == LogType.ABORT_TRANSACTION) {
                    xact.setStatus(Transaction.Status.RECOVERY_ABORTING);
                } else if (recordType == LogType.COMMIT_TRANSACTION) {
                    xact.setStatus(Transaction.Status.COMMITTING);
                }


            }

            if (logRec.type == LogType.END_CHECKPOINT) {
                for (Map.Entry<Long, Long> item : logRec.getDirtyPageTable().entrySet()) {
                    this.dirtyPageTable.put(item.getKey(), item.getValue());
                }


                for (Map.Entry<Long, Pair<Transaction.Status, Long>> item : logRec.getTransactionTable().entrySet()) {
                    long transNum = item.getKey();
                    Transaction.Status status = item.getValue().getFirst();
                    long recordLastLSN = item.getValue().getSecond();

                    TransactionTableEntry tableEntry = transactionTable.get(transNum);
                    tableEntry = createNewXact(tableEntry, transNum);
                    Transaction transaction = tableEntry.transaction;
                    if (transaction.getStatus() == Transaction.Status.COMPLETE) {
                        this.transactionTable.remove(transaction.getTransNum());
                        continue;
                    }
                    if (status == Transaction.Status.ABORTING) {
                        status = Transaction.Status.RECOVERY_ABORTING;
                    }
                    if (transaction.getStatus() != Transaction.Status.COMMITTING && transaction.getStatus() != Transaction.Status.RECOVERY_ABORTING) {
                        transaction.setStatus(status); // Record will always overwrite
                    }
                    tableEntry.lastLSN = Math.max(tableEntry.lastLSN, recordLastLSN); // Record will always overwrite if max

                }

                for (Map.Entry<Long, List<Long>> transNumPagesPair : logRec.getTransactionTouchedPages().entrySet()) {
                    long transNum = transNumPagesPair.getKey();
                    List<Long> pages = transNumPagesPair.getValue();
                    TransactionTableEntry tableEntry = transactionTable.get(transNum);
                    tableEntry = createNewXact(tableEntry, transNum);

                    Transaction transaction = tableEntry.transaction;
                    if (transaction.getStatus() == Transaction.Status.COMPLETE) {
                        transactionTable.remove(transaction.getTransNum());
                        continue;
                    }
                }
            }
        }

        for (Map.Entry<Long, TransactionTableEntry> item : this.transactionTable.entrySet()) {
            long transNum = item.getKey();

            TransactionTableEntry tableEntry = transactionTable.get(transNum);
            if (tableEntry == null) {
                continue;
            }

            Transaction transaction = tableEntry.transaction;

            if (transaction.getStatus() == Transaction.Status.RUNNING) {
                transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                long newAbortLSN = logManager.appendToLog(new AbortTransactionLogRecord(transaction.getTransNum(), tableEntry.lastLSN));
                tableEntry.lastLSN = newAbortLSN;
            }

            if (transaction.getStatus() == Transaction.Status.COMMITTING) {
                transaction.cleanup();
                transaction.setStatus(Transaction.Status.COMPLETE);
                logManager.appendToLog(new EndTransactionLogRecord(transaction.getTransNum(), tableEntry.lastLSN));
                if (!transactionTable.containsKey(transaction.getTransNum())) {

                    TransactionTableEntry newTransac = createNewXact(null, transaction.getTransNum());
                    if (newTransac.transaction.getStatus() == Transaction.Status.COMPLETE) {
                        transactionTable.remove(newTransac.transaction.getTransNum());
                    }

                } else {

                    transactionTable.remove(transaction.getTransNum());
                }
            }

        }
    }

    public boolean isPartitionType(LogRecord record) {
        return record.getType() == LogType.ALLOC_PART || record.getType() == LogType.FREE_PART ||
                record.getType() == LogType.UNDO_ALLOC_PART || record.getType() == LogType.UNDO_FREE_PART;
    }

    public boolean isPageType(LogRecord record) {
        return record.getType() == LogType.UNDO_FREE_PAGE || record.getType() == LogType.UNDO_UPDATE_PAGE ||
                record.getType() == LogType.UNDO_ALLOC_PAGE || record.getType() == LogType.UPDATE_PAGE ||
                record.getType() == LogType.ALLOC_PAGE || record.getType() == LogType.FREE_PAGE;
    }

    public TransactionTableEntry createNewXact(TransactionTableEntry entry, long transNum) {
        return createNewXactType(entry, transNum, null);
    }

    public TransactionTableEntry createNewXactType(TransactionTableEntry entry, long transNum, Transaction.Status status) {
        if (entry != null) return entry;
        Transaction xact = newTransaction.apply(transNum);
        if (status != null) {
            xact.setStatus(status);
        }
        startTransaction(xact);
        return this.transactionTable.get(xact.getTransNum());
    }
    /**
     * This method performs the redo pass of restart recovery.
     *
     * First, determine the starting point for REDO from the dirty page table.
     *
     * Then, scanning from the starting point, if the record is redoable and
     * - partition-related (Alloc/Free/UndoAlloc/UndoFree..Part), always redo it
     * - allocates a page (AllocPage/UndoFreePage), always redo it
     * - modifies a page (Update/UndoUpdate/Free/UndoAlloc....Page) in
     *   the dirty page table with LSN >= recLSN, the page is fetched from disk,
     *   the pageLSN is checked, and the record is redone if needed.
     */
    void restartRedo() {
        // TODO(proj5): implement
        long lowestRecLSN = Integer.MAX_VALUE;
        for(Long pageNum: dirtyPageTable.keySet()){
            lowestRecLSN = Math.min(lowestRecLSN, dirtyPageTable.get(pageNum));
        }
        Iterator<LogRecord> iter = logManager.scanFrom(lowestRecLSN);
        while(iter.hasNext()){
            LogRecord logRecord = iter.next();
            boolean isRedoable = false;
            boolean isPageRelated = false;
            boolean isInDPT = false;
            boolean isLSNNotLessThanRec = false;
            boolean isPageLSNLessThanLSN = false;

            if(logRecord.isRedoable()) isRedoable = true;

            if(logRecord instanceof UpdatePageLogRecord ||
                    logRecord instanceof AllocPageLogRecord ||
                    logRecord instanceof FreePageLogRecord ||
                    logRecord instanceof UndoUpdatePageLogRecord ||
                    logRecord instanceof UndoAllocPageLogRecord ||
                    logRecord instanceof UndoFreePageLogRecord){
                isPageRelated = true;
            }

            if(isPageRelated){
                long pageNum = logRecord.getPageNum().get();

                if(dirtyPageTable.containsKey(pageNum)) isInDPT = true;

                long LSN = logRecord.getLSN();
                long recLSN = dirtyPageTable.get(pageNum);
                if(LSN >= recLSN) isLSNNotLessThanRec = true;

                Page page = bufferManager.fetchPage(new DummyLockContext(), pageNum);
                long pageLSN = page.getPageLSN();
                if(pageLSN < LSN) isPageLSNLessThanLSN = true;
            }

            if(isRedoable && (!isPageRelated || (isInDPT&&isLSNNotLessThanRec&&isPageLSNLessThanLSN))){
                logRecord.redo(this, diskSpaceManager, bufferManager);
            }
        }
        return;
    }

    /**
     * This method performs the undo pass of restart recovery.

     * First, a priority queue is created sorted on lastLSN of all aborting
     * transactions.
     *
     * Then, always working on the largest LSN in the priority queue until we are done,
     * - if the record is undoable, undo it, and append the appropriate CLR
     * - replace the entry with a new one, using the undoNextLSN if available,
     *   if the prevLSN otherwise.
     * - if the new LSN is 0, clean up the transaction, set the status to complete,
     *   and remove from transaction table.
     */
    void restartUndo() {
        // TODO(proj5): implement
        PriorityQueue<Pair<Long, Long>> pq = new PriorityQueue<Pair<Long, Long>>(new PairFirstReverseComparator<Long, Long>());
        for (Map.Entry<Long, TransactionTableEntry> entry : transactionTable.entrySet()) {
            if (!entry.getValue().transaction.getStatus().equals(Transaction.Status.RECOVERY_ABORTING)) {
                continue;
            }
            pq.add(new Pair<>(entry.getValue().lastLSN, entry.getKey()));
        }

        while (pq.size() > 0) {
            Pair<Long, Long> curPair = pq.poll();
            TransactionTableEntry tte = transactionTable.get(curPair.getSecond());
            LogRecord curlr = logManager.fetchLogRecord(curPair.getFirst());
            if (curlr.isUndoable()) {
                if (aboutPage(curlr)) {
                    if (dirtyPageTable.containsKey(curlr.getPageNum().get())) {
                        if (curlr.getLSN() == dirtyPageTable.get(curlr.getPageNum().get())) {
                            dirtyPageTable.remove(curlr.getPageNum().get());
                        }
                    }
                }
                LogRecord tmplr = curlr.undo(tte.lastLSN);
                logManager.appendToLog(tmplr);
                tte.lastLSN = tmplr.getLSN();
                tmplr.redo(this, diskSpaceManager, bufferManager);
            }
            Long newLSN = curlr.getUndoNextLSN().isPresent() ? curlr.getUndoNextLSN().get() : curlr.getPrevLSN().get();
            if (newLSN == 0) {
                tte.transaction.cleanup();
                tte.transaction.setStatus(Transaction.Status.COMPLETE);
                logManager.appendToLog(new EndTransactionLogRecord(curPair.getSecond(), tte.lastLSN));
                transactionTable.remove(curPair.getSecond());
            } else {
                pq.add(new Pair<>(newLSN, curPair.getSecond()));
            }
        }
    }

    /**
     * Removes pages from the DPT that are not dirty in the buffer manager.
     * This is slow and should only be used during recovery.
     */
    void cleanDPT() {
        Set<Long> dirtyPages = new HashSet<>();
        bufferManager.iterPageNums((pageNum, dirty) -> {
            if (dirty) dirtyPages.add(pageNum);
        });
        Map<Long, Long> oldDPT = new HashMap<>(dirtyPageTable);
        dirtyPageTable.clear();
        for (long pageNum : dirtyPages) {
            if (oldDPT.containsKey(pageNum)) {
                dirtyPageTable.put(pageNum, oldDPT.get(pageNum));
            }
        }
    }

    // Helpers /////////////////////////////////////////////////////////////////
    /**
     * Comparator for Pair<A, B> comparing only on the first element (type A),
     * in reverse order.
     */
    private static class PairFirstReverseComparator<A extends Comparable<A>, B> implements
            Comparator<Pair<A, B>> {
        @Override
        public int compare(Pair<A, B> p0, Pair<A, B> p1) {
            return p1.getFirst().compareTo(p0.getFirst());
        }
    }
    boolean aboutPage(LogRecord lr) {
        if (lr.getPageNum().isPresent()) {
            return true;
        }
        return false;
    }
}
