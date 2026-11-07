#!/usr/bin/env python3
"""Linux reference agent for the ESP8266 v1 tunnel (stdlib only, not a chip emulator)."""
import argparse
import ipaddress
import io
import json
import os
import queue
import random
import select
import socket
import ssl
import struct
import threading
import time

AUTH, AUTH_OK, AUTH_ERROR = 1, 2, 3
OPEN, OPEN_OK, OPEN_ERROR = 16, 17, 18
DATA, WINDOW, FIN, CLOSED, RESET, PING, PONG = 32, 33, 34, 35, 36, 48, 49
TYPES = {AUTH, AUTH_OK, AUTH_ERROR, OPEN, OPEN_OK, OPEN_ERROR, DATA, WINDOW, FIN, CLOSED, RESET, PING, PONG}
HEADER = struct.Struct('!2sBBIII')
CHUNK, CREDIT = 1024, 4096


def exact(sock, count):
    chunks = bytearray()
    while len(chunks) < count:
        piece = sock.recv(count - len(chunks))
        if not piece:
            raise EOFError('tunnel closed')
        chunks.extend(piece)
    return bytes(chunks)


def read_frame(sock):
    magic, version, kind, stream, length, reserved = HEADER.unpack(exact(sock, HEADER.size))
    if (magic != b'IT' or version != 1 or reserved or kind not in TYPES
            or length > (CHUNK if kind == DATA else 512)
            or (kind == DATA and length == 0) or (kind in (FIN, CLOSED) and length)
            or ((kind <= 3 or kind >= 48) != (stream == 0))):
        raise ValueError('invalid frame')
    return kind, stream, exact(sock, length)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON key')
        result[key] = value
    return result


def object_payload(payload):
    result = json.loads(payload, object_pairs_hook=unique_object)
    if not isinstance(result, dict):
        raise ValueError('JSON object required')
    return result


def number(obj, key, low, high):
    value = obj.get(key)
    if type(value) is not int or not low <= value <= high:
        raise ValueError('invalid numeric field')
    return value


class MemorySocket:
    def __init__(self, data):
        self.data = io.BytesIO(data)

    def recv(self, size):
        return self.data.read(size)


class Flow:
    def __init__(self, agent, sid, request):
        self.agent, self.sid, self.request = agent, sid, request
        self.condition = threading.Condition()
        self.queue = queue.Queue(CREDIT + 2)  # at most CREDIT bytes + FIN/control
        self.sock = None
        self.stopped = threading.Event()
        self.send_credit = number(request, 'initial_window', CREDIT, CREDIT)
        self.receive_credit = CREDIT
        self.remote_fin = self.local_fin = self.ready = False
        self.active = time.monotonic()
        self.close_started = None

    def emit(self, kind, payload=None):
        self.agent.send(kind, self.sid, payload)

    def start(self):
        threading.Thread(target=self.connect, daemon=True).start()

    def connect(self):
        try:
            ip = ipaddress.IPv4Address(self.request['target_ip'])
            port = number(self.request, 'target_port', 1, 65535)
            timeout = number(self.request, 'connect_timeout_ms', 200, 5000) / 1000
            self.idle = number(self.request, 'idle_timeout_sec', 5, 3600)
            if (ip.is_multicast or ip.is_loopback or ip.is_unspecified or str(ip).endswith('.255')
                    or not any(ip in net for net in self.agent.networks)
                    or port not in self.agent.ports):
                self.stopped.set()
                self.emit(OPEN_ERROR, {'code': 'DENIED'})
                self.agent.release(self)
                return
            target = socket.create_connection((str(ip), port), timeout)
            with self.condition:
                if self.stopped.is_set():
                    target.close()
                    return
                self.sock = target
                target.settimeout(1)
                self.ready = True
                self.emit(OPEN_OK, {'initial_window': CREDIT})
            threading.Thread(target=self.write_target, daemon=True).start()
            self.read_target()
        except (OSError, ValueError, KeyError) as error:
            if not self.stopped.is_set():
                if not self.ready:
                    self.stopped.set()
                    self.emit(OPEN_ERROR, {'code': 'CONNECT_TIMEOUT' if isinstance(error, TimeoutError) else 'REFUSED'})
                    self.agent.release(self)
                else:
                    self.reset('TARGET_IO')

    def receive(self, kind, payload):
        with self.condition:
            if self.stopped.is_set():
                return
            if kind == RESET:
                object_payload(payload)
                self.stop()
                self.emit(CLOSED)
                self.agent.release(self)
                return
            if not self.ready:
                raise ValueError('stream not open')
            if kind == DATA:
                if self.remote_fin or len(payload) > self.receive_credit:
                    raise ValueError('invalid DATA/credit')
                self.receive_credit -= len(payload)
                self.queue.put_nowait(payload)
                self.active = time.monotonic()
            elif kind == WINDOW:
                credit = number(object_payload(payload), 'credit', 1, CREDIT)
                if self.send_credit + credit > CREDIT:
                    raise ValueError('credit overflow')
                self.send_credit += credit
                self.condition.notify_all()
            elif kind == FIN:
                if self.remote_fin:
                    raise ValueError('duplicate FIN')
                self.remote_fin = True
                self.close_started = time.monotonic()
                self.queue.put_nowait(None)
            else:
                raise ValueError('unexpected stream message')

    def write_target(self):
        try:
            while not self.stopped.is_set():
                try:
                    data = self.queue.get(timeout=.2)
                except queue.Empty:
                    continue
                if data is None:
                    self.sock.shutdown(socket.SHUT_WR)
                    with self.condition:
                        self.write_finished = True
                        self.finish_if_closed()
                    return
                # Preserve partial writes; a blocked target is bounded by close/idle watchdogs.
                offset = 0
                while offset < len(data) and not self.stopped.is_set():
                    try:
                        count = self.sock.send(data[offset:])
                        if count == 0:
                            raise OSError('target closed')
                        offset += count
                    except socket.timeout:
                        continue
                with self.condition:
                    if self.stopped.is_set():
                        return
                    self.receive_credit += len(data)
                    self.emit(WINDOW, {'credit': len(data)})
        except OSError as error:
            print('TARGET_WRITE_ERROR: ' + type(error).__name__, flush=True)
            self.reset('TARGET_IO')

    def read_target(self):
        try:
            while not self.stopped.is_set():
                with self.condition:
                    while self.send_credit == 0 and not self.stopped.is_set():
                        self.condition.wait(.2)
                    if self.stopped.is_set():
                        return
                    size = min(CHUNK, self.send_credit)
                try:
                    data = self.sock.recv(size)
                except socket.timeout:
                    continue
                with self.condition:
                    if self.stopped.is_set():
                        return
                    if not data:
                        self.local_fin = True
                        self.close_started = time.monotonic()
                        self.emit(FIN)
                        self.finish_if_closed()
                        return
                    self.send_credit -= len(data)
                    self.active = time.monotonic()
                    self.emit(DATA, data)
        except OSError as error:
            print('TARGET_READ_ERROR: ' + type(error).__name__, flush=True)
            self.reset('TARGET_IO')

    def finish_if_closed(self):
        if self.local_fin and getattr(self, 'write_finished', False):
            self.stop()
            self.emit(CLOSED)
            self.agent.release(self)

    def reset(self, code):
        with self.condition:
            if self.stopped.is_set():
                return
            print('STREAM_RESET: ' + code, flush=True)
            self.stop()
            try:
                self.emit(RESET, {'code': code})
                self.emit(CLOSED)
            finally:
                self.agent.release(self)

    def stop(self):
        with self.condition:
            self.stopped.set()
            self.condition.notify_all()
            if self.sock:
                try:
                    self.sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                self.sock.close()


class Agent:
    def __init__(self, sock, networks, ports):
        self.sock, self.networks, self.ports = sock, networks, ports
        self.outgoing = queue.Queue(128)
        self.authenticated = False
        self.flow_lock = threading.RLock()
        self.flow = None
        self.last_id = 0
        self.stopped = threading.Event()
        self.last_pong = time.monotonic()
        self.pending = set()
        self.writing = None

    def send(self, kind, sid=0, payload=None):
        if payload is None:
            payload = b''
        elif not isinstance(payload, bytes):
            payload = json.dumps(payload, separators=(',', ':')).encode()
        frame = HEADER.pack(b'IT', 1, kind, sid, len(payload), 0) + payload
        if not self.authenticated:
            self.sock.sendall(frame)
        else:
            try:
                self.outgoing.put_nowait(frame)
            except queue.Full:
                # Never block while holding a flow lock: overload closes the session.
                self.close()
                raise OSError('TLS output queue full')

    def release(self, flow):
        with self.flow_lock:
            if self.flow is flow:
                self.flow = None

    def run(self, auth):
        self.send(AUTH, payload=auth)
        kind, sid, payload = read_frame(self.sock)
        if kind != AUTH_OK:
            raise ValueError('authentication rejected')
        config = object_payload(payload)
        heartbeat = number(config, 'heartbeat_sec', 1, 60)
        timeout = number(config, 'timeout_sec', heartbeat * 2, 180)
        self.authenticated = True
        self.sock.setblocking(False)
        print('ONLINE', flush=True)
        threading.Thread(target=self.watch, args=(heartbeat, timeout), daemon=True).start()
        buffered = bytearray()
        pending = b''
        try:
            while not self.stopped.is_set():
                if not pending:
                    try:
                        pending = self.outgoing.get_nowait()
                        self.writing = time.monotonic()
                    except queue.Empty:
                        self.writing = None
                readable, writable, _ = select.select([self.sock], [self.sock] if pending else [], [], .05)
                if pending and (writable or readable):
                    try:
                        count = self.sock.send(pending)
                        if not count:
                            raise EOFError('TLS write closed')
                        pending = pending[count:]
                    except (ssl.SSLWantReadError, ssl.SSLWantWriteError):
                        pass
                if readable or self.sock.pending():
                    try:
                        data = self.sock.recv(8192)
                        if not data:
                            raise EOFError('TLS read closed')
                        buffered.extend(data)
                    except (ssl.SSLWantReadError, ssl.SSLWantWriteError):
                        pass
                while len(buffered) >= HEADER.size:
                    _, _, _, _, length, _ = HEADER.unpack_from(buffered)
                    if length > CHUNK:
                        raise ValueError('oversized frame')
                    if len(buffered) < HEADER.size + length:
                        break
                    kind, sid, payload = read_frame(MemorySocket(bytes(buffered[:HEADER.size + length])))
                    del buffered[:HEADER.size + length]
                    self.dispatch(kind, sid, payload)
        finally:
            self.close()

    def dispatch(self, kind, sid, payload):
        if kind == PONG:
            nonce = number(object_payload(payload), 'nonce', 0, 0xffffffff)
            with self.flow_lock:
                if nonce not in self.pending:
                    raise ValueError('unexpected PONG')
                self.pending.remove(nonce)
                self.last_pong = time.monotonic()
        elif kind == OPEN:
            request = object_payload(payload)
            with self.flow_lock:
                if sid <= self.last_id:
                    raise ValueError('reused stream id')
                self.last_id = sid
                if self.flow and not self.flow.stopped.is_set():
                    self.send(OPEN_ERROR, sid, {'code': 'BUSY'})
                else:
                    self.flow = Flow(self, sid, request)
                    self.flow.start()
        else:
            with self.flow_lock:
                flow = self.flow
            if flow is None or flow.sid != sid:
                if sid == self.last_id and kind in (WINDOW, RESET):
                    return
                raise ValueError('unknown stream')
            flow.receive(kind, payload)

    def watch(self, heartbeat, timeout):
        sent, nonce = 0, 0
        try:
            while not self.stopped.wait(.2):
                now = time.monotonic()
                if now - self.last_pong > timeout or (self.writing and now - self.writing > 10):
                    self.close()
                    return
                with self.flow_lock:
                    flow = self.flow
                if flow and flow.ready:
                    if flow.close_started and now - flow.close_started > 10:
                        flow.reset('CLOSE_TIMEOUT')
                    elif now - flow.active > flow.idle:
                        flow.reset('IDLE_TIMEOUT')
                if now - sent >= heartbeat:
                    nonce = (nonce + 1) & 0xffffffff
                    with self.flow_lock:
                        self.pending.add(nonce)
                    self.send(PING, 0, {'nonce': nonce})
                    sent = now
        except OSError:
            self.close()

    def close(self):
        self.stopped.set()
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()
        with self.flow_lock:
            flow = self.flow
        if flow:
            flow.stop()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True)
    parser.add_argument('--port', type=int, default=9443)
    parser.add_argument('--ca', help='PEM trust anchor; system CAs used if omitted')
    parser.add_argument('--device-id', required=True)
    parser.add_argument('--allow-cidr', action='append', required=True)
    parser.add_argument('--allow-port', action='append', type=int, required=True)
    parser.add_argument('--lan-ip', required=True)
    parser.add_argument('--netmask', default='255.255.255.0')
    parser.add_argument('--gateway', required=True)
    parser.add_argument('--once', action='store_true', help='exit after disconnection instead of reconnecting')
    args = parser.parse_args()
    credential = os.environ.get('TUNNEL_DEVICE_KEY')
    if not credential:
        parser.error('set TUNNEL_DEVICE_KEY')
    networks = [ipaddress.IPv4Network(net) for net in args.allow_cidr]
    context = ssl.create_default_context(cafile=args.ca)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    auth = dict(device_id=args.device_id, credential=credential, firmware='linux-simulator-v1',
                lan_ip=args.lan_ip, netmask=args.netmask, gateway=args.gateway)
    delay = 1
    while True:
        started = time.monotonic()
        agent = None
        try:
            raw = socket.create_connection((args.host, args.port), 10)
            try:
                sock = context.wrap_socket(raw, server_hostname=args.host)
            except BaseException:
                raw.close()
                raise
            sock.settimeout(90)
            agent = Agent(sock, networks, set(args.allow_port))
            agent.run(auth)
        except (OSError, ValueError, EOFError, queue.Full) as error:
            reason = str(error) if type(error) is ValueError else type(error).__name__
            print('DISCONNECTED: ' + reason, flush=True)  # no payload/credentials
        finally:
            if agent:
                agent.close()
        if args.once:
            return
        if time.monotonic() - started >= 60:
            delay = 1
        time.sleep(random.uniform(.8, 1.2) * delay)
        delay = min(30, delay * 2)


if __name__ == '__main__':
    main()
