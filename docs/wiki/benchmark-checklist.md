# Benchmark Checklist

Use this before claiming high-scale performance.

## Server Matrix

- Paper latest supported build.
- Purpur latest supported build.
- Folia latest supported build.
- Velocity network with at least 3 backends.
- Redis network mode with at least 3 backends.

## Scenarios

- 100, 300 and 600 online players.
- Global chat spam at controlled rates.
- Staff spy enabled for several staff accounts.
- Rules and swear filter enabled.
- Discord bridge enabled and disabled.
- Proxy mode `legacy`, `native`, and `redis`.

## Evidence

Capture:

- spark profiler link.
- CPU, heap and GC settings.
- Chatalyst config used.
- Message rate and player count.
- Any warnings from console.

Do not publish performance claims without reproducible configs and profiler output.
