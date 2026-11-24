#!/usr/bin/env python3
"""K2Go School M0 spike, experiment E1: how many live channels one teacher box holds.

Opens N simulated students (server-sent event connections) to the box over the network, then
publishes a few messages and measures, for each step of N: connections that opened, messages
that arrived, delivery delay (publisher and subscribers run on this PC, so one clock), and the
time to load one Kiwix page from the box while the channels are open.

The publisher must reach the box as localhost (the pub location is localhost only), for example
through `adb forward tcp:18085 tcp:8085`.

    python e1_load.py --box 192.168.1.167 --pub http://127.0.0.1:18085 --steps 50,100,200
Python 3.8+, standard library only.
"""
import argparse
import asyncio
import json
import statistics
import time
import urllib.request

CHANNEL = 'e1'


class Client:
    def __init__(self):
        self.ok = False
        self.got = {}       # seq -> delay in ms
        self.writer = None


async def run_client(c, host, port, run_id, opened):
    answered = False
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), 10)
        c.writer = writer
        writer.write(('GET /school/sub/%s HTTP/1.1\r\nHost: %s\r\n'
                      'Accept: text/event-stream\r\n\r\n' % (CHANNEL, host)).encode())
        await writer.drain()
        status = await asyncio.wait_for(reader.readline(), 10)
        c.ok = b' 200 ' in status
        answered = True
        opened.release()
        if not c.ok:
            return
        while True:
            line = await reader.readline()
            if not line:
                return
            if line.startswith(b'data: '):
                try:
                    m = json.loads(line[6:])
                except ValueError:
                    continue
                if m.get('run') == run_id:
                    c.got[m['seq']] = (time.time() - m['t']) * 1000
    except (OSError, asyncio.TimeoutError):
        if not answered:
            opened.release()


def publish(pub, msg):
    req = urllib.request.Request(pub + '/school/pub/' + CHANNEL, data=json.dumps(msg).encode(),
                                 method='POST')
    urllib.request.urlopen(req, timeout=10).read()


def subscribers(pub):
    req = urllib.request.Request(pub + '/school/pub/' + CHANNEL,
                                 headers={'Accept': 'application/json'})
    try:
        return json.loads(urllib.request.urlopen(req, timeout=10).read()).get('subscribers')
    except Exception:  # noqa: BLE001 - only a report value
        return None


def page_ms(url):
    t = time.time()
    try:
        urllib.request.urlopen(url, timeout=20).read()
        return round((time.time() - t) * 1000)
    except Exception:  # noqa: BLE001 - a failure is a result here
        return -1


async def step(n, args, port):
    run_id = '%d-%d' % (n, int(time.time()))
    opened = asyncio.Semaphore(0)
    clients = [Client() for _ in range(n)]
    tasks = []
    for i, c in enumerate(clients):
        tasks.append(asyncio.ensure_future(run_client(c, args.box, port, run_id, opened)))
        if i % 50 == 49:
            await asyncio.sleep(0.2)
    for _ in range(n):
        try:
            await asyncio.wait_for(opened.acquire(), 30)
        except asyncio.TimeoutError:
            break
    await asyncio.sleep(1)
    open_ok = sum(1 for c in clients if c.ok)
    subs = await asyncio.get_running_loop().run_in_executor(None, subscribers, args.pub)
    loop = asyncio.get_running_loop()
    for seq in range(args.msgs):
        try:
            await loop.run_in_executor(None, publish, args.pub,
                                       {'run': run_id, 'seq': seq, 't': time.time()})
        except OSError as e:  # the box drops the publisher too when it is full
            print('%5d publish failed: %s' % (n, e), flush=True)
            break
        await asyncio.sleep(args.interval)
    kiwix = await loop.run_in_executor(
        None, page_ms, 'http://%s:%d/kiwix/content/k2go_welcome/A/index.html' % (args.box, port))
    await asyncio.sleep(1)
    delays = [d for c in clients for d in c.got.values()]
    got = len(delays)
    want = open_ok * args.msgs
    for c in clients:
        if c.writer:
            c.writer.close()
    for t in tasks:
        t.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)
    q = statistics.quantiles(delays, n=20) if len(delays) > 1 else [0] * 19
    print('%5d %6d %6s %7d/%-7d %6.0f %6.0f %6.0f %7d' % (
        n, open_ok, subs, got, want, q[9] if delays else -1, q[18] if delays else -1,
        max(delays) if delays else -1, kiwix), flush=True)
    await asyncio.sleep(args.rest)


async def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--box', required=True, help='box address on the class network')
    ap.add_argument('--port', type=int, default=8085)
    ap.add_argument('--pub', required=True, help='publisher base URL that reaches the box as localhost')
    ap.add_argument('--steps', default='50,100,200,400,800')
    ap.add_argument('--msgs', type=int, default=5)
    ap.add_argument('--interval', type=float, default=1.0)
    ap.add_argument('--rest', type=float, default=3.0)
    args = ap.parse_args()
    print('    N   open   subs  received/expected  p50ms  p95ms  maxms  kiwixms')
    for n in [int(s) for s in args.steps.split(',')]:
        await step(n, args, args.port)


if __name__ == '__main__':
    asyncio.run(main())
