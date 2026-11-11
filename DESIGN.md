# StreamGuard — design notes

Why the aggregation core is built the way it is, and the tradeoffs behind each choice. Everything
else (filter, REST API, detectors, dashboard) is built on top of it.

## Why a bucketed sliding window

The job is to answer windowed questions — "req/sec, error rate and p50/p95/p99 over the last
5 minutes" — continuously, under a write rate far higher than the query rate. Two obvious baselines:

- **Keep every raw event, scan on query.** Simple and exact, but query cost is O(events). At a few
  million events in-window a snapshot takes hundreds of milliseconds (measured: see the README's
  Performance section), and memory grows without bound.
- **A single running counter.** O(1) and tiny, but it can never *expire* old data — it answers
  "since start", not "last 5 minutes".

The bucketed window is the middle path. Time is cut into fixed 1-second buckets; each bucket holds
counters and a latency histogram for its slice. Writes land in the current (head) bucket in O(1);
a query sums the buckets overlapping the range. Cost is **O(buckets in range), independent of how
many events those buckets absorbed** — so query latency stays flat (single-digit µs) as volume
grows 200×, while memory is bounded at `maxBuckets` regardless of traffic. That flat-vs-linear gap
is the core property StreamGuard is built around.

## Per-bucket counters: LongAdder, not AtomicLong

Each bucket's request/error counts are `LongAdder`s. The head bucket is a contention hot spot —
every writer thread increments the *same* counter at once.

- **`AtomicLong`** would funnel all writers through one CAS on one cache line; under contention they
  spin retrying and throughput collapses (false-sharing + CAS failure storms).
- **`LongAdder`** spreads increments across internal cells, one per contending thread, and only sums
  them on read (`sum()`). Writes are the common case and effectively uncontended; the rare read pays
  the sum. That matches our write-heavy, read-light access pattern exactly.
- **Hand-rolled striped counters** would be the same idea, re-implemented worse. `LongAdder` *is* the
  striped counter from the JDK, so we use it.

The latency histogram is an `AtomicIntegerArray` (39 bins). A plain `int[]` would silently lose
increments under concurrent writers, since `bins[i]++` is a read-modify-write. `incrementAndGet` per
bin is the cost of **0% sample loss**, asserted exactly in the benchmark. A `LongAdder[39]` would cut
write contention further but costs 39 objects per bucket; at our volumes the atomic array wins on
memory and is fast enough (bins spread contention naturally, since latencies vary).

## Latency percentiles: fixed histogram, not a reservoir or t-digest

Percentiles come from a fixed-bin histogram with linear interpolation inside the holding bin, the
same approach as Prometheus histograms. The 38 bin bounds grow exponentially, each about 25% wider
than the last (1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20 … 8000, 10000 ms), plus an overflow bin.

Equal-width bins don't work for web latency. With 100ms bins, most requests finish inside the first
one, so every percentile would read as q × 100ms (p50 50ms, p99 99ms) whatever the real latency.
Exponential bins make the error relative instead (within about 25% of the value at any scale), with
fewer bins. Finding the bin is a binary search over the bounds, about 6
comparisons per write.

- **Sorted reservoir / exact sampling** gives better tail accuracy but needs per-query sorting
  (O(n log n)) or reservoir bookkeeping, and reservoirs are awkward to *merge* across buckets.
- **t-digest / HDR** gives excellent tail accuracy with bounded memory and is the right call if the
  SLA is on p99.9. It's heavier than the current accuracy target needs.
- **Fixed histogram** has the property that matters most here: bins are **trivially mergeable** —
  summing two buckets' histograms is element-wise addition, so a range query just folds the buckets
  together and reads percentiles off the sum. The cost is resolution: a percentile is only known to
  within its bin (interpolated), and everything ≥10s lands in the overflow bin.

## Bucket rotation and late events

Writers never allocate on the fast path: if the head bucket's id matches the event's time slice,
the writer just updates it. Allocation of a new bucket happens only at a **bucket boundary** (once
per second of wall time, not per event), and only then under a single `rotateLock`:

- The lock is taken **once per boundary**, not per event, so it's off the hot path. We re-check the
  head inside the lock (double-checked) so two writers racing across the boundary don't both append —
  the loser sees the winner's bucket and reuses it. This is simpler and safer than a lock-free CAS
  append on a deque, and the lock is essentially never contended (≈1 acquisition/sec per window).
- Eviction of expired buckets happens under the same lock, so two evictors can't pop the same
  head-of-queue. Readers iterate the `ConcurrentLinkedDeque` lock-free and tolerate concurrent
  append/evict.
- **Late events** (a timestamp sampled just before a second boundary but recorded just after it) are
  common. `findLate` walks back from the head; since such events are almost always one bucket late,
  the scan stops in a step or two. If no bucket exists for that exact slice, the event is folded into
  the next newer bucket — the deque can't insert in the middle and the skew is under one bucket.
  Events older than the whole window are dropped.

## Failure modes and their handling

- **Clock skew / non-monotonic time.** Bucket ids derive from `timestamp / bucketMillis`. A backward
  jump is handled as a late event (bounded by the window); a large forward jump rotates and evicts as
  if time passed. We inject the clock (`LongSupplier`) so tests drive these paths deterministically.
- **Histogram overflow.** Latencies ≥10s saturate into the last bin. p99 then reads as 10s rather
  than a precise value: acceptable, and visible.
- **Memory under load.** Bounded by `maxBuckets`, not event count. A traffic spike costs counter
  increments, not allocation. Per-client windows keep only one minute of buckets (the longest
  window the security checks read), so a flood of distinct clients costs 60 buckets each, not 900.
- **Unbounded route keys.** Requests are keyed by their Spring MVC route template, and requests no
  handler matched collapse into `NOT_FOUND` / `REDIRECTION` / `UNKNOWN`, so random URLs can't
  create unlimited per-route windows.
- **Idle keys.** `isIdle()` / eviction let per-endpoint and per-IP windows be reclaimed so tracking
  many short-lived keys doesn't leak.
- **Read/write races.** A snapshot taken mid-write may include or miss the in-flight event by one;
  for monitoring aggregates this eventual-consistency is fine and far cheaper than locking readers.

## What changes for multi-node

Today StreamGuard is deliberately **in-process**: it monitors its host JVM, like Micrometer, not a
fleet. Scaling out changes the hard parts:

- **Aggregation stays local; aggregation-of-aggregates goes central.** Each node keeps its bucketed
  window and exports *bucket* snapshots (counters + histograms), not raw events. Because the
  histogram is mergeable, a central collector sums nodes the same way a query sums buckets — the core
  data structure already supports fan-in.
- **Percentiles across nodes** come from merging histograms, never from averaging each node's p99
  (which is statistically meaningless). The fixed-bin choice pays off again here.
- **Time alignment** becomes real work: buckets must be aligned to a shared epoch so node A's
  12:00:03 bucket sums with node B's, and late/out-of-order arrival across the network widens the
  "late event" problem into a watermarking one (á la streaming engines).
- **Forecasting/anomaly detection** would move to the aggregated series, and alert dedup/suppression
  becomes necessary so N nodes don't fire N copies of the same spike.

StreamGuard today runs in-process on a single node. The multi-node design above is the roadmap;
see "Roadmap" in the README.
