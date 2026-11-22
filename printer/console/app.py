"""Read-only print intake console. PostgreSQL is reached only over WireGuard."""
import functools
import hmac
import ipaddress
import json
import os
import secrets
import sqlite3
import time
import urllib.error
import urllib.request
from urllib.parse import urlencode
from datetime import datetime, timedelta
from pathlib import Path

import psycopg
from psycopg.rows import dict_row
from flask import Flask, Response, abort, jsonify, redirect, render_template, request, send_file, session, url_for
from werkzeug.middleware.proxy_fix import ProxyFix
from werkzeug.security import check_password_hash

PREFIX = '/console'
DEFAULT_RECEIPT_LAYOUT = {
    'companySize': 28, 'titleSize': 34, 'bodySize': 20, 'totalSize': 24,
    'tableSpaces': 5, 'lineSpacing': 2, 'companyBold': False, 'titleBold': True,
    'bodyBold': False, 'totalBold': False, 'companyAlign': 'center', 'titleAlign': 'center',
    'bodyAlign': 'left', 'totalAlign': 'left'
}
DEFAULT_NATIVE_LAYOUT = {
    'font': 'A', 'companyWidth': 1, 'companyHeight': 1, 'titleWidth': 2, 'titleHeight': 1,
    'bodyWidth': 1, 'bodyHeight': 1, 'totalWidth': 1, 'totalHeight': 1,
    'lineSpacingDots': 16, 'tableSpaces': 3, 'blocks': [
        {'type':'section','section':'company','text':'','gapDots':0,'feedDots':0,'width':1,'height':1,'bold':False,'align':'center'},
        {'type':'section','section':'title','text':'','gapDots':0,'feedDots':0,'width':2,'height':1,'bold':True,'align':'center'},
        {'type':'section','section':'body','text':'','gapDots':0,'feedDots':0,'width':1,'height':1,'bold':False,'align':'left'},
        {'type':'rule','section':'','text':'','gapDots':0,'feedDots':0,'width':1,'height':1,'bold':False,'align':'left'},
        {'type':'section','section':'table','text':'','gapDots':0,'feedDots':0,'width':1,'height':1,'bold':False,'align':'left'},
        {'type':'rule','section':'','text':'','gapDots':0,'feedDots':0,'width':1,'height':1,'bold':False,'align':'left'},
        {'type':'section','section':'total','text':'','gapDots':0,'feedDots':0,'width':1,'height':1,'bold':True,'align':'left'},
        {'type':'section','section':'address','text':'','gapDots':0,'feedDots':0,'width':1,'height':1,'bold':False,'align':'left'}
    ]
}


def create_app(overrides=None):
    app = Flask(__name__, static_url_path=PREFIX + '/static')
    app.config.update(
        SECRET_KEY=os.environ.get('CONSOLE_SECRET'),
        ADMIN_USER=os.environ.get('CONSOLE_USER', 'admin'),
        ADMIN_HASH=os.environ.get('CONSOLE_PASSWORD_HASH', ''),
        DATABASE_URL=os.environ.get('CONSOLE_DATABASE_URL', ''),
        ADMIN_API_URL=os.environ.get('CONSOLE_ADMIN_API_URL', ''),
        ADMIN_API_KEY=os.environ.get('CONSOLE_ADMIN_API_KEY', ''),
        DEVICE_ID=os.environ.get('CONSOLE_DEVICE_ID', ''),
        TEST_PRINTER_IP=os.environ.get('CONSOLE_TEST_PRINTER_IP', ''),
        LEGACY_FIRMWARE=os.environ.get('CONSOLE_LEGACY_FIRMWARE', ''),
        LEGACY_FIRMWARE_VERSION=os.environ.get('CONSOLE_LEGACY_FIRMWARE_VERSION', '1.0.0'),
        STATE_DB=os.environ.get('CONSOLE_STATE_DB', '/var/lib/printer-console/security.sqlite'),
        SESSION_COOKIE_NAME='printer_console', SESSION_COOKIE_PATH=PREFIX,
        SESSION_COOKIE_SECURE=True, SESSION_COOKIE_HTTPONLY=True, SESSION_COOKIE_SAMESITE='Strict',
        PERMANENT_SESSION_LIFETIME=timedelta(hours=8), MAX_CONTENT_LENGTH=16_384,
    )
    if overrides:
        app.config.update(overrides)
    if not app.config['SECRET_KEY'] or not app.config['ADMIN_HASH']:
        raise RuntimeError('Console credentials must be configured')
    app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1)
    Path(app.config['STATE_DB']).parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(app.config['STATE_DB']) as state:
        state.execute('CREATE TABLE IF NOT EXISTS failures (ip TEXT, at REAL)')
        state.execute('CREATE INDEX IF NOT EXISTS failures_ip_at ON failures(ip,at)')

    def query(sql, params=()):
        with psycopg.connect(app.config['DATABASE_URL'], connect_timeout=5, row_factory=dict_row,
                             options='-c default_transaction_read_only=on -c statement_timeout=5000') as db:
            return db.execute(sql, params).fetchall()
    app.extensions['query'] = query

    def logged_in():
        return session.get('user') == app.config['ADMIN_USER']

    def protect(function):
        @functools.wraps(function)
        def wrapped(*args, **kwargs):
            if not logged_in():
                if '/api/' in request.path:
                    return jsonify(error='请先登录'), 401
                return redirect(url_for('login'))
            return function(*args, **kwargs)
        return wrapped

    def csrf():
        expected = session.get('csrf')
        provided = request.form.get('csrf', '') or request.headers.get('X-CSRF-Token', '')
        if not expected or not hmac.compare_digest(expected, provided):
            abort(400, description='页面已过期，请刷新后重试')

    def admin_action(path, payload):
        if not isinstance(payload, dict):
            return jsonify(error='请求格式无效'), 400
        if not app.config['ADMIN_API_URL'] or not app.config['ADMIN_API_KEY'] or not app.config['DEVICE_ID']:
            return jsonify(error='打印机管理接口尚未配置'), 503
        payload['deviceId'] = app.config['DEVICE_ID']
        payload['operator'] = session['user']
        body = json.dumps(payload).encode()
        req = urllib.request.Request(app.config['ADMIN_API_URL'].rstrip('/') + path, body,
                                     {'Content-Type': 'application/json', 'X-Console-Key': app.config['ADMIN_API_KEY']}, method='POST')
        try:
            with urllib.request.urlopen(req, timeout=180) as response:
                return jsonify(json.loads(response.read()))
        except urllib.error.HTTPError as error:
            try: detail = json.loads(error.read())
            except Exception: detail = {'error': '打印服务拒绝了请求'}
            return jsonify(detail), error.code
        except Exception:
            app.logger.warning('Printer admin API unavailable')
            return jsonify(error='打印服务暂时不可用'), 503

    def admin_get(path):
        if not app.config['ADMIN_API_URL'] or not app.config['ADMIN_API_KEY'] or not app.config['DEVICE_ID']:
            return jsonify(error='打印机管理接口尚未配置'), 503
        req = urllib.request.Request(app.config['ADMIN_API_URL'].rstrip('/') + path,
                                     headers={'X-Console-Key': app.config['ADMIN_API_KEY']})
        try:
            with urllib.request.urlopen(req, timeout=10) as response:
                return jsonify(json.loads(response.read()))
        except urllib.error.HTTPError as error:
            return jsonify(error='管理状态暂不可用'), error.code
        except Exception:
            return jsonify(error='打印服务暂时不可用'), 503

    def admin_image_get(path):
        if not app.config['ADMIN_API_URL'] or not app.config['ADMIN_API_KEY'] or not app.config['DEVICE_ID']:
            return jsonify(error='打印机管理接口尚未配置'), 503
        req = urllib.request.Request(app.config['ADMIN_API_URL'].rstrip('/') + path,
                                     headers={'X-Console-Key': app.config['ADMIN_API_KEY']})
        try:
            with urllib.request.urlopen(req, timeout=15) as response:
                return Response(response.read(), mimetype='image/png')
        except urllib.error.HTTPError as error:
            try: detail = json.loads(error.read())
            except Exception: detail = {'error': '打印预览暂不可用'}
            return jsonify(detail), error.code
        except Exception:
            app.logger.warning('Printer preview API unavailable')
            return jsonify(error='打印预览暂不可用'), 503

    @app.after_request
    def headers(response):
        response.headers['Cache-Control'] = 'no-store'
        response.headers['X-Content-Type-Options'] = 'nosniff'
        response.headers['X-Frame-Options'] = 'DENY'
        response.headers['Referrer-Policy'] = 'same-origin'
        response.headers['Content-Security-Policy'] = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
        return response

    @app.get(PREFIX + '/healthz')
    def health():
        app.extensions['query']('SELECT 1')
        return jsonify(status='ok')

    @app.route(PREFIX + '/login', methods=['GET', 'POST'])
    def login():
        if request.method == 'GET' and logged_in():
            return redirect(url_for('index'))
        session.setdefault('csrf', secrets.token_urlsafe(32))
        error = None
        status = 200
        if request.method == 'POST':
            csrf()
            ip = request.remote_addr or 'unknown'
            now = time.time()
            with sqlite3.connect(app.config['STATE_DB']) as state:
                state.execute('DELETE FROM failures WHERE at < ?', (now - 900,))
                count = state.execute('SELECT COUNT(*) FROM failures WHERE ip = ?', (ip,)).fetchone()[0]
                if count >= 5:
                    error, status = '尝试次数过多，请 15 分钟后重试。', 429
                else:
                    # Always perform the password hash check, including for unknown usernames.
                    valid = check_password_hash(app.config['ADMIN_HASH'], request.form.get('password', ''))
                    if request.form.get('username') == app.config['ADMIN_USER'] and valid:
                        state.execute('DELETE FROM failures WHERE ip = ?', (ip,))
                        session.clear()
                        session.update(user=app.config['ADMIN_USER'], csrf=secrets.token_urlsafe(32))
                        session.permanent = True
                        return redirect(url_for('index'))
                    state.execute('INSERT INTO failures VALUES (?,?)', (ip, now))
                    error, status = '账号或密码错误。', 401
        return render_template('login.html', csrf=session['csrf'], error=error), status

    @app.post(PREFIX + '/logout')
    @protect
    def logout():
        csrf()
        session.clear()
        return redirect(url_for('login'))

    @app.get(PREFIX + '/')
    @protect
    def index():
        firmware_path = app.config['LEGACY_FIRMWARE']
        firmware_available = bool(firmware_path and Path(firmware_path).is_file())
        return render_template('index.html', csrf=session['csrf'], username=session['user'],
                               firmware_available=firmware_available,
                               firmware_version=app.config['LEGACY_FIRMWARE_VERSION'],
                               device_id=app.config['DEVICE_ID'],
                               test_printer_ip=app.config['TEST_PRINTER_IP'])

    @app.get(PREFIX + '/downloads/esp8266-core-2.4.2-tls10-v<version>.zip')
    @protect
    def legacy_firmware(version):
        if version != app.config['LEGACY_FIRMWARE_VERSION']:
            abort(404)
        firmware_path = app.config['LEGACY_FIRMWARE']
        if not firmware_path or not Path(firmware_path).is_file():
            abort(404)
        return send_file(firmware_path, as_attachment=True,
                         download_name=f'esp8266-core-2.4.2-tls10-v{version}.zip',
                         mimetype='application/zip', max_age=0)

    @app.get(PREFIX + '/api/jobs')
    @protect
    def jobs():
        try:
            page = int(request.args.get('page', '1'))
            if not 1 <= page <= 10000:
                raise ValueError()
            clauses, params = [], []
            term = request.args.get('q', '').strip()
            if len(term) > 128:
                raise ValueError()
            if term:
                clauses.append("(parsed_payload->>'invoiceNo' ILIKE %s OR parsed_payload->>'customer' ILIKE %s)")
                params.extend(['%' + term + '%'] * 2)
            source_ip = request.args.get('ip', '').strip()
            if source_ip:
                ipaddress.ip_address(source_ip)
                clauses.append('source_ip = %s::inet')
                params.append(source_ip)
            status = request.args.get('status', 'queued') or 'queued'
            if status == 'all':
                clauses.append("status <> 'discarded'")
            elif status:
                if status not in {'accepted', 'queued', 'leased', 'sending', 'sent_unknown', 'delivery_unknown', 'received', 'printing', 'succeeded', 'render_failed', 'failed', 'expired', 'cancelled', 'discarded'}:
                    raise ValueError()
                clauses.append('status = %s'); params.append(status)
            # date inputs are ISO dates in the deployment's Asia/Shanghai timezone.
            for key, op, extra in [('from', '>=', 0), ('to', '<', 1)]:
                value = request.args.get(key, '')
                if value:
                    date = datetime.strptime(value, '%Y-%m-%d') + timedelta(days=extra)
                    clauses.append(f"received_at {op} (%s::timestamp AT TIME ZONE 'Asia/Shanghai')")
                    params.append(date.isoformat())
        except (ValueError, OverflowError):
            return jsonify(error='筛选条件无效，请检查页码、IP 或日期。'), 400
        where = ' WHERE ' + ' AND '.join(clauses) if clauses else ''
        total = app.extensions['query']('SELECT COUNT(*) AS total FROM print_jobs' + where, params)[0]['total']
        rows = app.extensions['query']("""SELECT id, source, host(source_ip) AS source_ip, status,
            render_status, delivery_status, artifact_size, received_at,
            parsed_payload->>'invoiceNo' AS invoice_no, parsed_payload->>'customer' AS customer
            FROM print_jobs""" + where + ' ORDER BY received_at DESC, id DESC LIMIT 30 OFFSET %s', params + [(page-1)*30])
        return jsonify(total=total, page=page, page_size=30, rows=serializable(rows))

    @app.get(PREFIX + '/api/jobs/discarded')
    @protect
    def discarded_jobs():
        try:
            page = int(request.args.get('page', '1'))
            if not 1 <= page <= 10000:
                raise ValueError()
        except ValueError:
            return jsonify(error='页码无效。'), 400
        bounds = """WITH day_bounds AS (
            SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date AS today
        )"""
        today_filter = "received_at >= (today::timestamp AT TIME ZONE 'Asia/Shanghai') AND received_at < ((today + 1)::timestamp AT TIME ZONE 'Asia/Shanghai')"
        today_total = app.extensions['query'](bounds + " SELECT COUNT(*) AS total FROM print_jobs, day_bounds WHERE status = 'discarded' AND " + today_filter)[0]['total']
        rows = app.extensions['query'](bounds + """ SELECT id, host(source_ip) AS source_ip, received_at,
            error_message, left(raw_payload, 500) AS raw_preview
            FROM print_jobs, day_bounds
            WHERE status = 'discarded' AND """ + today_filter +
            ' ORDER BY received_at DESC, id DESC LIMIT 30 OFFSET %s', [(page-1)*30])
        return jsonify(today_total=today_total, page=page, page_size=30, rows=serializable(rows))

    @app.get(PREFIX + '/api/jobs/<uuid:job_id>')
    @protect
    def detail(job_id):
        rows = app.extensions['query']("""SELECT id, source, host(source_ip) AS source_ip, status,
            render_status, delivery_status, received_at, rendered_at, completed_at, updated_at,
            payload_encoding, parsed_payload, left(raw_payload, 200000) AS raw_payload,
            length(raw_payload) > 200000 AS raw_truncated, artifact_size, artifact_sha256,
            target_device_id, error_code, error_message
            FROM print_jobs WHERE id = %s""", (job_id,))
        if not rows:
            abort(404)
        return jsonify(serializable(rows[0]))

    @app.post(PREFIX + '/api/admin/scan')
    @protect
    def scan_printers():
        csrf()
        try:
            network = ipaddress.ip_network(request.json.get('cidr', ''), strict=True)
            if network.version != 4 or network.prefixlen < 24 or network.num_addresses > 256:
                raise ValueError()
        except (ValueError, AttributeError, TypeError):
            return jsonify(error='扫描范围必须是单个 IPv4 /24 或更小网段'), 400
        return admin_action('/scan', {'cidr': str(network)})

    @app.get(PREFIX + '/api/admin/scan/<uuid:scan_id>')
    @protect
    def scan_status(scan_id):
        return admin_get('/scan/' + str(scan_id))

    @app.get(PREFIX + '/api/devices/status')
    @protect
    def device_status():
        return admin_get('/devices/status')

    @app.post(PREFIX + '/api/admin/test-print')
    @protect
    def test_print():
        csrf()
        return admin_action('/test-print', request.json or {})

    @app.post(PREFIX + '/api/admin/printer-status')
    @protect
    def printer_status():
        csrf()
        return admin_action('/printer-status', request.json or {})

    @app.post(PREFIX + '/api/admin/reprint')
    @protect
    def reprint():
        csrf()
        payload = request.json or {}
        if payload.get('mode') == 'image':
            payload.setdefault('layout', DEFAULT_RECEIPT_LAYOUT)
        if payload.get('mode') == 'native':
            payload.setdefault('nativeSettings', DEFAULT_NATIVE_LAYOUT)
        return admin_action('/reprint', payload)

    @app.get(PREFIX + '/api/admin/reprint-preview/<uuid:job_id>')
    @protect
    def reprint_preview(job_id):
        layout = request.args.get('layout')
        query = {'jobId': str(job_id), 'format': request.args.get('format', 'image')}
        native_layout = request.args.get('nativeLayout')
        if native_layout:
            try:
                parsed = json.loads(native_layout)
                if not isinstance(parsed, dict) or len(native_layout) > 1024:
                    raise ValueError()
                query['nativeLayout'] = json.dumps(parsed, separators=(',', ':'))
            except (ValueError, TypeError):
                return jsonify(error='ESC/POS 参数无效'), 400
        if layout:
            try:
                parsed = json.loads(layout)
                if not isinstance(parsed, dict) or len(layout) > 1024:
                    raise ValueError()
                query['layout'] = json.dumps(parsed, separators=(',', ':'))
            except (ValueError, TypeError):
                return jsonify(error='排版参数无效'), 400
        return admin_image_get('/reprint/preview?' + urlencode(query))

    @app.get(PREFIX + '/api/admin/reprint-preview/sample')
    @protect
    def reprint_preview_sample():
        layout = request.args.get('layout')
        query = {'format': request.args.get('format', 'image')}
        native_layout = request.args.get('nativeLayout')
        if native_layout:
            try:
                parsed = json.loads(native_layout)
                if not isinstance(parsed, dict) or len(native_layout) > 1024:
                    raise ValueError()
                query['nativeLayout'] = json.dumps(parsed, separators=(',', ':'))
            except (ValueError, TypeError):
                return jsonify(error='ESC/POS 参数无效'), 400
        if layout:
            try:
                parsed = json.loads(layout)
                if not isinstance(parsed, dict) or len(layout) > 1024:
                    raise ValueError()
                query['layout'] = json.dumps(parsed, separators=(',', ':'))
            except (ValueError, TypeError):
                return jsonify(error='排版参数无效'), 400
        suffix = '?' + urlencode(query) if query else ''
        return admin_image_get('/reprint/preview/sample' + suffix)

    @app.errorhandler(psycopg.Error)
    def database_error(error):
        app.logger.warning('Console database request failed: %s', type(error).__name__)
        return jsonify(error='数据库暂时不可用，请稍后重试。'), 503

    return app


def serializable(value):
    def encode(item):
        if isinstance(item, datetime):
            return item.isoformat()
        return str(item)
    return json.loads(json.dumps(value, default=encode, ensure_ascii=False))
