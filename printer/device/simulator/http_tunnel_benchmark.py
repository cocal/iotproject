#!/usr/bin/env python3
"""Measure HTTP request throughput through one ESP8266 TCP tunnel mapping."""

import argparse
import concurrent.futures
import statistics
import time
import urllib.error
import urllib.request


def percentile(values, fraction):
    ordered = sorted(values)
    if not ordered:
        return 0.0
    index = round((len(ordered) - 1) * fraction)
    return ordered[index]


def request(url, timeout):
    started = time.perf_counter()
    req = urllib.request.Request(url, headers={"Connection": "close", "Cache-Control": "no-cache"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            body = response.read()
            return response.status, len(body), time.perf_counter() - started, ""
    except urllib.error.HTTPError as error:
        return error.code, 0, time.perf_counter() - started, f"HTTP {error.code}"
    except Exception as error:  # Report connection resets and timeouts per request.
        return 0, 0, time.perf_counter() - started, type(error).__name__


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url", nargs="?", default="http://127.0.0.1:18084/logs.txt")
    parser.add_argument("-n", "--requests", type=int, default=20, help="total requests (1..500)")
    parser.add_argument("-c", "--concurrency", type=int, default=1, help="parallel requests (1..8)")
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--interval", type=float, default=0.0,
                        help="delay between request starts; only supported with concurrency 1")
    parser.add_argument("--duration", type=float, default=0.0,
                        help="run for this many seconds instead of a fixed request count (max 600)")
    args = parser.parse_args()
    if (not 1 <= args.requests <= 500 or not 1 <= args.concurrency <= 8 or args.timeout <= 0
            or args.interval < 0 or (args.interval > 0 and args.concurrency != 1)
            or args.duration < 0 or args.duration > 600):
        parser.error("requests must be 1..500, concurrency 1..8, timeout positive; duration max 600s; interval requires concurrency 1")

    started = time.perf_counter()
    if args.concurrency == 1:
        results = []
        request_limit = 2000 if args.duration else args.requests
        while len(results) < request_limit and (not args.duration or time.perf_counter() - started < args.duration):
            results.append(request(args.url, args.timeout))
            if args.interval and len(results) < request_limit and (not args.duration or time.perf_counter() - started < args.duration):
                time.sleep(args.interval)
    else:
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            results = list(pool.map(lambda _: request(args.url, args.timeout), range(args.requests)))
    elapsed = time.perf_counter() - started

    good = [result for result in results if 200 <= result[0] < 300]
    latencies = [result[2] for result in good]
    sizes = [result[1] for result in good]
    errors = {}
    for status, _, _, error in results:
        if error:
            key = error or f"HTTP {status}"
            errors[key] = errors.get(key, 0) + 1

    print(f"URL: {args.url}")
    planned = f"duration: {args.duration:.1f}s" if args.duration else f"requests: {args.requests}"
    print(f"{planned}, completed: {len(results)}, concurrency: {args.concurrency}, interval: {args.interval:.3f}s, elapsed: {elapsed:.3f}s")
    print(f"Success: {len(good)}/{len(results)}, success TPS: {len(good) / elapsed:.2f}")
    if latencies:
        print("Latency (successful): "
              f"avg={statistics.mean(latencies):.3f}s "
              f"p50={percentile(latencies, .50):.3f}s "
              f"p95={percentile(latencies, .95):.3f}s "
              f"max={max(latencies):.3f}s")
        print(f"Response bytes: min={min(sizes)}, max={max(sizes)}")
    if errors:
        print("Errors: " + ", ".join(f"{name}={count}" for name, count in sorted(errors.items())))
    print("Note: each ESP8266 supports one active target TCP stream; concurrency above 1 tests contention, not parallel device capacity.")
    return 0 if len(good) == len(results) else 2


if __name__ == "__main__":
    raise SystemExit(main())
