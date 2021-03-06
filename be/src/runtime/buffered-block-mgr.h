// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#ifndef IMPALA_RUNTIME_BUFFERED_BLOCK_MGR
#define IMPALA_RUNTIME_BUFFERED_BLOCK_MGR

#include <boost/shared_ptr.hpp>

#include "runtime/disk-io-mgr.h"
#include "runtime/tmp-file-mgr.h"

namespace impala {

class MemPool;
class RuntimeState;

// BufferedBlockMgr is used to allocate and manage blocks of data using a fixed memory
// budget. Available memory is split into a pool of fixed-size memory buffers.
// When a client allocates or requests a block, the block is assigned a buffer from this
// pool and is 'pinned' in memory. Clients can also unpin a block, allowing the manager
// to reassign its buffer to a different block.
//
// BufferedBlockMgr reserves one buffer per disk ('block_write_threshold_') for itself.
// When the number of free buffers falls below 'block_write_threshold', unpinned blocks
// are persisted in Last-In_First-Out order. (It is assumed that unpinned blocks are
// re-read in FIFO order). TmpFileMgr is used to obtain file handles to write to within
// the tmp directories configured for Impala.
//
// It is expected to have one BufferedBlockMgr per PlanFragmentInstance (ideally per
// query). All allocations that can grow proportional to input size and might need
// to spill to disk should allocate from the same BufferedBlockMgr.
//
// A client must pin a block in memory to read/write its contents and unpin it
// when it is no longer in active use. BufferedBlockMgr guarantees that
// a) The memory buffer assigned to a block is not removed or released while it is pinned
// b) The contents of an unpinned block will be available on a subsequent call to pin.
//    Thus, an unpinned block must be persisted before its buffer is released or assigned
//    to a different block.
//
// The Client API is as follows:
// GetNewBlock() returns a new pinned block.
// A Block supports the following operations
// Pin(): Pins a block to a buffer in memory, and reads its contents from disk if
//   necessary. If there are no free buffers, waits for a buffer to become available.
//   Invoked before the contents of a block are read or written. The block
//   will be maintained in memory until Unpin() is called.
// Unpin(): Invoked to indicate the block is not in active use. The block is added to a
//   list of unpinned blocks. Unpinned blocks are only written when the number of free
//   blocks falls below the 'block_write_threshold'.
// Delete(): Invoked to deallocate a block. The buffer associated with the block is
//   immediately released and its on-disk location (if any) reused.
//
// Close() frees all memory and disk space and is called when a query is closed or
// cancelled. Close() is idempotent.
//
// The block manager is thread safe with the following caveat:
//   A single block cannot be pinned and used simultaneously by multiple clients.
// TODO: When a block is read from disk, data is copied from the IOMgr buffer to the
// block manager's buffer. This should be avoided in the common case where these buffers
// are of the same size.
// TODO: see if the one big lock is a bottleneck. Break it up.
class BufferedBlockMgr {
 private:
  struct BufferDescriptor;

 public:
  // A client of the BufferedBlockMgr. There is a single BufferedBlockMgr per plan
  // fragment and all operators that need blocks from it should be a separate client.
  // Each client has the option to reserve a number of blocks that it can claim later.
  // The remaining memory that is not reserved by any clients is free for all and
  // available to all clients.
  // This is an opaque handle.
  // TODO: move the APIs to client we don't need to pass the BufferedBlockMgr around.
  struct Client;

  // A fixed-size block of data that may be be persisted to disk. The state of the block
  // is maintained by the block manager and is described by 3 bools
  // is_pinned_ = True if the block is pinned. The block has a non-null buffer_desc_,
  //   buffer_desc_ cannot be in the free buffer list and the block cannot be in
  //   unused_blocks_ or unpinned_blocks_. Newly allocated blocks are pinned.
  // in_write_ = True if a write has been issued but not completed for this block.
  //   The block cannot be in the unpinned_blocks_ and must have a non-null buffer_desc_
  //   that's not in the free buffer list. It may be pinned or unpinned.
  // is_deleted_ = True if Delete() has been called on a block. After this, no API call
  //   is valid on the block.
  //
  // Pin() and Unpin() can be invoked on a block any number of times before Delete().
  // When a pinned block is unpinned for the first time, it is added to the
  // unpinned_blocks_ list and its buffer is removed from the free list.
  // If it is pinned or deleted at any time while it is on the unpinned list, it is
  // simply removed from that list. When it is dequeued from that list and enqueued
  // for writing, in_write_ is set to true. The block may be pinned, unpinned or deleted
  // while in_write_ is true. After the write has completed, the block's buffer will be
  // returned to the free buffer list if it is no longer pinned, and the block itself
  // will be put on the unused blocks list if Delete() was called.
  //
  // A block MUST have a non-null buffer_desc_ if
  // i) is_pinned_ is true (i.e. the client is using it), or
  // ii) in_write_ is true, (i.e. IO mgr is using it), or
  // iii) It is on the unpinned list (buffer has not been persisted.)
  //
  // In addition to the block manager API, Block exposes Allocate(), ReturnAllocation()
  // and BytesRemaining() to allocate and free memory within a block, and buffer() and
  // valid_data_len() to read/write the contents of a block. These are not thread-safe.
  class Block : public InternalQueue<Block>::Node {
   public:
    // Pins a block in memory - assigns a free buffer to a block and reads it from
    // disk if necessary. If there are no free blocks and no unpinned blocks,
    // *pinned is set to false and the block is not pinned.
    Status Pin(bool* pinned);

    // Unpin a block - add it to the list of unpinned blocks maintained by the block
    // manager. Is non-blocking.
    Status Unpin();

    // Delete a block. Its buffer is released and on-disk location can be over-written.
    // Non-blocking.
    Status Delete();

    // Allocates the specified number of bytes from this block.
    template <typename T> T* Allocate(int size) {
      DCHECK_GE(BytesRemaining(), size);
      uint8_t* current_location = buffer_desc_->buffer + valid_data_len_;
      valid_data_len_ += size;
      return reinterpret_cast<T*>(current_location);
    }

    // Return the number of remaining bytes that can be allocated in this block.
    int BytesRemaining() const {
      return block_mgr_->block_size_ - valid_data_len_;
    }

    // Return size bytes from the most recent allocation.
    void ReturnAllocation(int size) {
      DCHECK_GE(valid_data_len_, size);
      valid_data_len_ -= size;
    }

    // Pointer to start of the block data in memory. Only guaranteed to be valid if the
    // block is pinned.
    uint8_t* buffer() const { return buffer_desc_->buffer; }

    // Return the number of bytes allocated in this block.
    int64_t valid_data_len() const { return valid_data_len_; }

    bool is_pinned() const { return is_pinned_; }

    int64_t buffer_len() const { return block_mgr_->block_size(); }

    // Debug helper method to print the state of a block.
    std::string DebugString() const;

   private:
    friend class BufferedBlockMgr;

    Block(BufferedBlockMgr* block_mgr);

    // Initialize the state of a block and set the number of bytes allocated to 0.
    void Init();

    // Debug helper method to validate the state of a block.
    bool Validate() const;

    // Pointer to the buffer associated with the block. NULL if the block is not in
    // memory and cannot be changed while the block is pinned or being written.
    BufferDescriptor* buffer_desc_;

    // Parent block manager object. Responsible for maintaining the state of the block.
    BufferedBlockMgr* block_mgr_;

    // The client that owns this block.
    Client* client_;

    // WriteRange object representing the on-disk location used to persist a block.
    // Is created the first time a block is persisted, and retained until the block
    // object is destroyed. The file location and offset in write_range_ are valid
    // throughout the lifetime of this object, but the data and length in the
    // write_range_ are only valid while the block is being written.
    // write_range_ instance is owned by the block manager.
    DiskIoMgr::WriteRange* write_range_;

    // Length of valid (i.e. allocated) data within the block.
    int64_t valid_data_len_;

    // Block state variables. The block's buffer can be freed only if is_pinned_ and
    // in_write_ are both false.
    // TODO: this might be better expressed as an enum.

    // is_pinned_ is true while the block is pinned by a client.
    bool is_pinned_;

    // in_write_ is set to true when the block is enqueued for writing via DiskIoMgr,
    // and set to false when the write is complete.
    bool in_write_;

    // True if the block is deleted by the client.
    bool is_deleted_;

    // Condition variable for when there is a specific client waiting for this block.
    // Only used if client_local_ is true.
    boost::condition_variable write_complete_cv_;

    // If true, this block is being written out so the underlying buffer can be
    // transferred to another block from the same client. We don't want this buffer
    // getting picked up by another client.
    bool client_local_;
  }; // class Block

  // Create a block manager with the specified mem_limit. If a block mgr with the
  // same query id has already been created, that block mgr is returned.
  static Status Create(RuntimeState* state, MemTracker* parent,
      RuntimeProfile* profile, int64_t mem_limit, int64_t buffer_size,
      boost::shared_ptr<BufferedBlockMgr>* block_mgr);

  ~BufferedBlockMgr();

  // Registers a client with num_reserved_buffers. The returned client is owned
  // by the BufferedBlockMgr and has the same lifetime as it.
  // We allow oversubscribing the reserved buffers. It is likely that the
  // num_reserved_buffers will be very pessimistic for small
  // queries and we don't want to fail all of them with mem limit exceeded.
  // The min reserved buffers is often independent of data size and we still want
  // to run small queries with very small limits.
  // If tracker is non-NULL, buffers used by this client are reflected in tracker.
  Status RegisterClient(int num_reserved_buffers, MemTracker* tracker,
      RuntimeState* state, Client** client);

  // Lowers the buffer reservation of the client to num_buffers. This doesn't change
  // buffers that have already been given to this client.
  // num_buffers must be less than the current reservation.
  // TODO: we could relax this and let the reservation go up but there isn't a use
  // case now.
  void LowerBufferReservation(Client* client, int num_buffers);

  // Return the number of blocks a block manager will reserve for its I/O buffers.
  static int GetNumReservedBlocks() {
    return TmpFileMgr::num_tmp_devices();
  }

  // Return a new pinned block. If there is no memory for this block, *block will be
  // set to NULL.
  // This function will try to allocate new memory for the block up to the limit.
  // Otherwise it will (conceptually) write out a unpinned block and use that memory.
  // The caller can pass a non-NULL 'unpin_block' to transfer memory from 'unpin_block'
  // to the new block. If unpin_block is non-NULL, the new block can never fail to
  // get a buffer. The semantics of this are:
  //   - If unpin_block is non-NULL, it must be pinned.
  //   - If the call succeeds, unpin_block is unpinned.
  //   - If there is no memory pressure, block will get a new block.
  //   - If there is memory pressure, block will get the buffer from unpin_block.
  Status GetNewBlock(Client* client, Block* unpin_block, Block** block);

  // Cancels the block mgr. All subsequent calls fail with Status::CANCELLED.
  // Idempotent.
  void Cancel();

  // Dumps block mgr state. Grabs lock.
  std::string DebugString();

  int num_free_buffers() const {
    return free_buffers_.size();
  }

  // The number of allocated buffers that can be simultaneously pinned by clients.
  int available_allocated_buffers() const {
    return all_buffers_.size();
  }

  int num_pinned_buffers(Client* client) const;
  int num_reserved_buffers_remaining(Client* client) const;

  int64_t block_size() const { return block_size_; }
  int64_t bytes_allocated() const;

  RuntimeProfile* profile() { return profile_.get(); }

 private:
  friend struct Client;

  // Descriptor for a single memory buffer in the pool.
  struct BufferDescriptor : public InternalQueue<BufferDescriptor>::Node {
    // Start of the buffer
    uint8_t* buffer;

    // Block that this buffer is assigned to. May be NULL.
    Block* block;

    BufferDescriptor(uint8_t* buf)
      : buffer(buf), block(NULL) {
    }
  }; // struct BufferDescriptor

  BufferedBlockMgr(RuntimeState* state, MemTracker* parent, int64_t mem_limit,
      int64_t block_size);

  // Initialize the counters to track the block manager behavior.
  void InitCounters(RuntimeProfile* profile);

  // PinBlock(), UnpinBlock(), DeleteBlock() perform the actual work of Block::Pin(),
  // Unpin() and Delete(). The lock_ must be taken by the caller.
  Status PinBlock(Block* block, bool* pinned);
  Status UnpinBlock(Block* block);
  Status DeleteBlock(Block* block);

  // Finds a buffer for a block and pins it. If the block's buffer has not been evicted,
  // removes the block from the unpinned list and sets *in_mem = true.
  // Otherwise, this function gets a new buffer by:
  //   1. Allocating a new buffer if possible.
  //   2. Using a buffer from the free list (which is populated by moving blocks from
  //      the unpinned list by writing them out).
  // If we can't get a buffer (no more memory, nothing in the unpinned and free lists,
  // this function returns with the block unpinned.
  // TODO: this function is shared by all spilling operators in a query. We need to
  // see if the locking is a bottleneck here.
  Status FindBufferForBlock(Block* block, bool* in_mem);

  // Writes unpinned blocks via DiskIoMgr until one of the following is true:
  // 1) The number of outstanding writes >= (block_write_threshold_ - num free buffers)
  // 2) There are no more unpinned blocks
  // Assumes the block manager lock_ is already taken. Is not blocking.
  Status WriteUnpinnedBlocks();

  // Issues the write for this block to the io mgr.
  Status WriteUnpinnedBlock(Block* block);

  // Callback used by DiskIoMgr to indicate a block write has completed.
  // write_status is the status of the write. is_cancelled_ is set to true if
  // write_status is not Status::OK. Returns the block's buffer to the free buffers
  // list if it is no longer pinned. Returns the block itself to the free blocks list
  // if it has been deleted.
  void WriteComplete(Block* block, const Status& write_status);

  // Return a deleted block to the list of free blocks. Assumes the block's buffer has
  // already been returned to the free buffers list. Non-blocking.
  // Thread-safe.
  void ReturnUnusedBlock(Block* block);

  // Checks unused_blocks_ for an unused block object, else allocates a new one.
  // Non-blocking and takes no locks.
  Block* GetUnusedBlock(Client* client);

  // Used to debug the state of the block manager. Lock must already be taken.
  bool Validate() const;
  std::string DebugInternal() const;

  // Size of a block in bytes.
  const int64_t block_size_;

  // Unpinned blocks are written when the number of free buffers is below this threshold.
  // Equal to the number of disks.
  const int block_write_threshold_;

  // The number of unreserved buffers. This can be less than 0 if we've oversubscribed
  // the number of reserved buffers.
  AtomicInt<int> num_unreserved_buffers_;

  // The total number of reserved buffers by all clients.
  AtomicInt<int> total_reserved_buffers_;

  // The number of unreserved buffers that are currently pinned. Must be >= 0.
  AtomicInt<int> num_unreserved_pinned_buffers_;

  TUniqueId query_id_;
  ObjectPool obj_pool_;

  // Track buffers allocated by the block manager.
  boost::scoped_ptr<MemTracker> mem_tracker_;

  // Protects the block and buffer lists below and changes to block state.
  boost::mutex lock_;

  // Number of outstanding writes (Writes issued but not completed).
  // This does not include client-local writes.
  int num_outstanding_writes_;

  // Signal availability of free buffers.
  boost::condition_variable buffer_available_cv_;

  // List of blocks is_pinned_ = false AND are not on DiskIoMgr's write queue.
  // Blocks are added to and removed from the back of the list. (i.e. in LIFO order).
  // Blocks in this list must have is_pinned_ = false, in_write_ = false,
  // is_deleted_ = false.
  InternalQueue<Block> unpinned_blocks_;

  // List of blocks that have been deleted and are no longer in use.
  // Can be reused in GetNewBlock(). Blocks in this list must be in the Init'ed state,
  // i.e. buffer_desc_ = NULL, is_pinned_ = false, in_write_ = false,
  // is_deleted_ = false, valid_data_len = 0.
  InternalQueue<Block> unused_blocks_;

  // List of buffers that can be assigned to a block in Pin() or GetNewBlock().
  // These buffers either have no block associated with them or are associated with an
  // an unpinned block that has been persisted. That is, either block = NULL or
  // (!block->is_pinned_  && !block->in_write_  && !unpinned_blocks_.Contains(block)).
  InternalQueue<BufferDescriptor> free_buffers_;

  // All allocated buffers.
  std::vector<BufferDescriptor*> all_buffers_;

  // Temporary physical file handle, (one per tmp device) to which blocks may be written.
  // Blocks are round-robined across these files.
  boost::ptr_vector<TmpFileMgr::File> tmp_files_;

  // Index into tmp_files_ denoting the file to which the next block to be persisted
  // will be written.
  int next_block_index_;

  // DiskIoMgr handles to read and write blocks.
  DiskIoMgr* io_mgr_;
  DiskIoMgr::RequestContext* io_request_context_;

  // Memory pool from which buffers are allocated.
  boost::scoped_ptr<MemPool> buffer_pool_;

  // If true, the block manager is cancelled and all API calls return
  // Status::CANCELLED. Set to true on Close() or if there was an error writing a block.
  bool is_cancelled_;

  // Counters and timers to track behavior.
  boost::scoped_ptr<RuntimeProfile> profile_;

  // These have a fixed value for the lifetime of the manager and show memory usage.
  RuntimeProfile::Counter* mem_limit_counter_;
  RuntimeProfile::Counter* mem_used_counter_;
  RuntimeProfile::Counter* block_size_counter_;

  // Total number of blocks created.
  RuntimeProfile::Counter* created_block_counter_;

  // Number of deleted blocks reused.
  RuntimeProfile::Counter* recycled_blocks_counter_;

  // Number of Pin() calls that did not require a disk read.
  RuntimeProfile::Counter* buffered_pin_counter_;

  // Time taken for disk reads.
  RuntimeProfile::Counter* disk_read_timer_;

  // Time spent waiting for a free buffer.
  RuntimeProfile::Counter* buffer_wait_timer_;

  // Number of writes issued
  RuntimeProfile::Counter* writes_issued_counter_;

  // Number of writes outstanding (issued but not completed)
  RuntimeProfile::Counter* outstanding_writes_counter_;

  // Protects query_to_block_mgrs_
  static boost::mutex static_block_mgrs_lock_;

  // All per-query BufferedBlockMgr objects that are in use.  For memory management, this
  // map contains only weak ptrs. BufferedBlockMgrs that are handed out are shared ptrs.
  // When all the shared ptrs are no longer referenced, the BufferedBlockMgr
  // d'tor will be called at which point the weak ptr will be removed from the map.
  typedef boost::unordered_map<TUniqueId, boost::weak_ptr<BufferedBlockMgr> >
      BlockMgrsMap;
  static BlockMgrsMap query_to_block_mgrs_;

}; // class BufferedBlockMgr

} // namespace impala.

#endif
