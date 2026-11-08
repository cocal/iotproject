import re
import sys
import tempfile
import unittest
from pathlib import Path
from werkzeug.security import generate_password_hash
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import create_app


class ConsoleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.password_hash = generate_password_hash('test-password')

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.app = create_app(dict(TESTING=True, SECRET_KEY='test-secret', ADMIN_HASH=self.password_hash,
                                  STATE_DB=self.temp.name+'/state.db', SESSION_COOKIE_SECURE=False))
        self.client = self.app.test_client()
        self.calls = []
        def query(sql, params=()):
            self.calls.append((sql, params))
            if 'COUNT(*)' in sql:
                return [{'total': 1}]
            return [{'id':'1','source_ip':'192.0.2.1','customer':'<script>alert(1)</script>'}]
        self.app.extensions['query'] = query

    def tearDown(self):
        self.temp.cleanup()

    def token(self):
        response=self.client.get('/console/login')
        return re.search(r'name="csrf" value="([^"]+)"',response.text).group(1)

    def login(self):
        return self.client.post('/console/login',data={'csrf':self.token(),'username':'admin','password':'test-password'})

    def test_authentication_and_csrf(self):
        self.assertEqual(401,self.client.get('/console/api/jobs').status_code)
        self.assertEqual(302,self.client.get('/console/').status_code)
        self.assertEqual(400,self.client.post('/console/login',data={'username':'admin','password':'test-password'}).status_code)
        self.assertEqual(302,self.login().status_code)
        self.assertEqual(200,self.client.get('/console/').status_code)
        self.assertEqual(400,self.client.post('/console/logout').status_code)
        token=re.search(r'name="csrf" value="([^"]+)"',self.client.get('/console/').text).group(1)
        self.assertEqual(302,self.client.post('/console/logout',data={'csrf':token}).status_code)
        self.assertEqual(401,self.client.get('/console/api/jobs').status_code)

    def test_rate_limit(self):
        token=self.token()
        for _ in range(5):
            self.assertEqual(401,self.client.post('/console/login',data={'csrf':token,'username':'admin','password':'wrong'}).status_code)
        self.assertEqual(429,self.client.post('/console/login',data={'csrf':token,'username':'admin','password':'test-password'}).status_code)

    def test_filters_bound_and_validated(self):
        self.login()
        result=self.client.get('/console/api/jobs',query_string={'q':"' OR 1=1 --",'ip':'192.0.2.1','from':'2026-11-01','to':'2026-11-08'})
        self.assertEqual(200,result.status_code)
        sql,params=self.calls[-1]
        self.assertNotIn("' OR 1=1",sql)
        self.assertIn("%' OR 1=1 --%",params)
        self.assertEqual(1,result.json['total'])
        for args in ({'ip':'bad'},{'page':'-1'},{'status':"' OR 1=1"},{'from':'bad-date'}):
            self.assertEqual(400,self.client.get('/console/api/jobs',query_string=args).status_code)
        self.assertEqual('no-store',result.headers['Cache-Control'])

    def test_routes_and_assets(self):
        with self.client.get('/console/static/app.js') as response:
            self.assertEqual(200,response.status_code)
        with self.client.get('/console/static/style.css') as response:
            self.assertEqual(200,response.status_code)
        self.assertIn("script-src 'self'",self.client.get('/console/login').headers['Content-Security-Policy'])
        self.login()
        self.assertEqual(404,self.client.get('/console/api/jobs/not-a-uuid').status_code)

if __name__=='__main__': unittest.main()
