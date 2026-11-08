"""Read-only print intake console. PostgreSQL is reached only over WireGuard."""
import functools
import hmac
import ipaddress
import json
import os
import secrets
import sqlite3
import time
from datetime import datetime, timedelta
from pathlib import Path

import psycopg
from psycopg.rows import dict_row
from flask import Flask, abort, jsonify, redirect, render_template, request, session, url_for
from werkzeug.middleware.proxy_fix import ProxyFix
from werkzeug.security import check_password_hash

PREFIX = '/console'


def create_app(overrides=None):
    app = Flask(__name__, static_url_path=PREFIX + '/static')
    app.config.update(
        SECRET_KEY=os.environ.get('CONSOLE_SECRET'),
        ADMIN_USER=os.environ.get('CONSOLE_USER', 'admin'),
        ADMIN_HASH=os.environ.get('CONSOLE_PASSWORD_HASH', ''),
        DATABASE_URL=os.environ.get('CONSOLE_DATABASE_URL', ''),
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
        provided = request.form.get('csrf', '')
        if not expected or not hmac.compare_digest(expected, provided):
            abort(400, description='页面已过期，请刷新后重试')

    @app.after_request
    def headers(response):
        response.headers['Cache-Control'] = 'no-store'
        response.headers['X-Content-Type-Options'] = 'nosniff'
        response.headers['X-Frame-Options'] = 'DENY'
        response.headers['Referrer-Policy'] = 'same-origin'
        response.headers['Content-Security-Policy'] = "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
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
        return render_template('index.html', csrf=session['csrf'], username=session['user'])

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
            status = request.args.get('status', '')
            if status:
                if status not in {'accepted', 'queued', 'leased', 'received', 'printing', 'succeeded', 'render_failed', 'failed', 'expired', 'cancelled'}:
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
