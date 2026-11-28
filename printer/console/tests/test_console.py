import re
import sys
import tempfile
import unittest
import json
from urllib.parse import parse_qs, urlparse
from pathlib import Path
from unittest.mock import patch
from werkzeug.security import generate_password_hash
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import create_app


class ConsoleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.password_hash = generate_password_hash('test-password')

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.firmware_path = Path(self.temp.name) / 'legacy.zip'
        self.firmware_path.write_bytes(b'private-firmware')
        self.app = create_app(dict(TESTING=True, SECRET_KEY='test-secret', ADMIN_HASH=self.password_hash,
                                  STATE_DB=self.temp.name+'/state.db', SESSION_COOKIE_SECURE=False,
                                  LEGACY_FIRMWARE=str(self.firmware_path), LEGACY_FIRMWARE_VERSION='1.0.1',
                                  DEVICE_ID='qingliu-esp8266', TEST_PRINTER_IP='192.168.180.94'))
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
        self.assertIn("status = %s",sql)
        self.assertIn('queued',params)
        self.client.get('/console/api/jobs',query_string={'status':''})
        self.assertIn('queued',self.calls[-1][1])
        self.assertNotIn("' OR 1=1",sql)
        self.assertIn("%' OR 1=1 --%",params)
        self.assertEqual(1,result.json['total'])
        for args in ({'ip':'bad'},{'page':'-1'},{'status':"' OR 1=1"},{'from':'bad-date'}):
            self.assertEqual(400,self.client.get('/console/api/jobs',query_string=args).status_code)
        self.client.get('/console/api/jobs',query_string={'status':'all'})
        self.assertIn("status <> 'discarded'",self.calls[-1][0])
        self.client.get('/console/api/jobs',query_string={'status':'discarded'})
        self.assertIn('status = %s',self.calls[-1][0])
        self.assertIn('discarded',self.calls[-1][1])
        self.assertEqual('no-store',result.headers['Cache-Control'])

    def test_routes_and_assets(self):
        with self.client.get('/console/static/app.js') as response:
            self.assertEqual(200,response.status_code)
        with self.client.get('/console/static/style.css') as response:
            self.assertEqual(200,response.status_code)
        csp=self.client.get('/console/login').headers['Content-Security-Policy']
        self.assertIn("script-src 'self'",csp)
        self.assertIn("img-src 'self' blob:",csp)
        self.login()
        page=self.client.get('/console/').text
        self.assertIn('name="reprint-mode" value="original"',page)
        self.assertIn('name="reprint-mode" value="image"',page)
        self.assertIn('name="reprint-mode" value="driver"',page)
        self.assertNotIn('name="reprint-mode" value="native"',page)
        self.assertNotIn('<option value="native">',page)
        self.assertIn('id="receipt-editor"',page)
        self.assertIn('<dialog id="layout-editor">',page)
        self.assertIn('id="open-layout-editor"',page)
        self.assertIn('id="layout-profile"',page)
        self.assertIn('id="reprint-profile"',page)
        self.assertIn('表格列间距（空格）',page)
        self.assertIn('data-layout="tableSpaces" type="number" min="1" max="20"',page)
        self.assertNotIn('<dialog id="detail"><section id="layout-editor"',page)
        self.assertIn('data-layout="bodyBold"',page)
        self.assertIn('id="image-preview"',page)
        self.assertIn('id="detail-image-preview"',page)
        self.assertIn('打印效果预览',page)
        self.assertIn('id="open-layout-editor"',page)
        self.assertIn('id="target-ip" value="192.168.180.94"',page)
        self.assertEqual(404,self.client.get('/console/api/jobs/not-a-uuid').status_code)

    def test_discarded_table_is_authenticated_and_scoped_to_today(self):
        self.assertEqual(401,self.client.get('/console/api/jobs/discarded').status_code)
        self.login()
        response=self.client.get('/console/api/jobs/discarded?page=1')
        self.assertEqual(200,response.status_code)
        self.assertEqual(1,response.json['today_total'])
        self.assertIn("status = 'discarded'",self.calls[-2][0])
        self.assertIn("AT TIME ZONE 'Asia/Shanghai'",self.calls[-2][0])
        self.assertIn('raw_preview',self.calls[-1][0])
        self.assertIn('今日废弃报文',self.client.get('/console/').text)

    def test_legacy_firmware_download_requires_login(self):
        path='/console/downloads/esp8266-core-2.4.2-tls10-v1.0.1.zip'
        self.assertEqual(302,self.client.get(path).status_code)
        self.login()
        page=self.client.get('/console/').text
        self.assertIn(path,page)
        self.assertIn('版本 1.0.1',page)
        self.assertIn('测试打印',page)
        self.assertIn('192.168.180.94',page)
        with self.client.get(path) as response:
            self.assertEqual(200,response.status_code)
            self.assertEqual(b'private-firmware',response.data)
            self.assertIn('esp8266-core-2.4.2-tls10-v1.0.1.zip',response.headers['Content-Disposition'])
            self.assertEqual('no-store',response.headers['Cache-Control'])
        self.assertEqual(404,self.client.get('/console/downloads/esp8266-core-2.4.2-tls10-v9.9.9.zip').status_code)

    def test_admin_actions_require_login_csrf_and_bounded_scan_range(self):
        self.assertEqual(401,self.client.post('/console/api/admin/scan',json={'cidr':'10.0.0.0/24'}).status_code)
        self.login()
        self.assertEqual(400,self.client.post('/console/api/admin/scan',json={'cidr':'10.0.0.0/16'}).status_code)
        response=self.client.post('/console/api/admin/scan',json={'cidr':'10.0.0.0/24'})
        self.assertEqual(400,response.status_code)
        response=self.client.post('/console/api/admin/scan',json={'cidr':'10.0.0.0/24'},headers={'X-CSRF-Token':self.client.get('/console/').text.split('name="csrf" value="')[1].split('"')[0]})
        self.assertEqual(503,response.status_code)

    def test_device_status_requires_login_and_proxies_server_snapshot(self):
        self.assertEqual(401,self.client.get('/console/api/devices/status').status_code)
        self.app.config.update(ADMIN_API_URL='http://printer-api.internal/internal/console',
                               ADMIN_API_KEY='test-api-key')
        self.login()
        class Response:
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def read(self): return b'{"devices":[{"deviceId":"qingliu-esp8266","online":true,"available":false},{"deviceId":"sh-pudong","online":true,"available":true}],"checkedAt":"2026-11-19T10:00:00Z"}'
        with patch('app.urllib.request.urlopen',return_value=Response()) as send:
            response=self.client.get('/console/api/devices/status')
        self.assertEqual(200,response.status_code)
        self.assertTrue(response.json['devices'][0]['online'])
        self.assertFalse(response.json['devices'][0]['available'])
        self.assertTrue(response.json['devices'][1]['online'])
        self.assertTrue(send.call_args.args[0].full_url.endswith('/devices/status'))

    def test_test_print_action_uses_configured_device_and_csrf(self):
        self.app.config.update(ADMIN_API_URL='http://printer-api.internal/internal/console',
                               ADMIN_API_KEY='test-api-key')
        self.login()
        token=re.search(r'name="csrf" value="([^"]+)"',self.client.get('/console/').text).group(1)
        class Response:
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def read(self): return b'{"result":"sent_unknown","bytes":128}'
        with patch('app.urllib.request.urlopen',return_value=Response()) as send:
            response=self.client.post('/console/api/admin/test-print',json={'ip':'192.168.180.94','port':9100},headers={'X-CSRF-Token':token})
        self.assertEqual(200,response.status_code)
        self.assertEqual({'result':'sent_unknown','bytes':128},response.json)
        request=send.call_args.args[0]
        forwarded=json.loads(request.data)
        self.assertEqual('qingliu-esp8266',forwarded['deviceId'])
        self.assertEqual('192.168.180.94',forwarded['ip'])
        self.assertEqual(9100,forwarded['port'])

    def test_printer_status_action_forwards_authorized_request(self):
        self.app.config.update(ADMIN_API_URL='http://printer-api.internal/internal/console',
                               ADMIN_API_KEY='test-api-key')
        self.login()
        token=re.search(r'name="csrf" value="([^"]+)"',self.client.get('/console/').text).group(1)
        class Response:
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def read(self): return b'{"result":"response_received","bytes":["16","12","04","00"],"bytesReceived":4}'
        with patch('app.urllib.request.urlopen',return_value=Response()) as send:
            response=self.client.post('/console/api/admin/printer-status',json={'ip':'192.168.180.94','port':9100},headers={'X-CSRF-Token':token})
        self.assertEqual(200,response.status_code)
        self.assertEqual(['16','12','04','00'],response.json['bytes'])
        request=send.call_args.args[0]
        self.assertTrue(request.full_url.endswith('/printer-status'))
        forwarded=json.loads(request.data)
        self.assertEqual('qingliu-esp8266',forwarded['deviceId'])
        self.assertEqual('192.168.180.94',forwarded['ip'])

    def test_reprint_forwards_mode_without_image_payload(self):
        self.app.config.update(ADMIN_API_URL='http://printer-api.internal/internal/console',
                               ADMIN_API_KEY='test-api-key')
        self.login()
        token=re.search(r'name="csrf" value="([^"]+)"',self.client.get('/console/').text).group(1)
        class Response:
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def read(self): return b'{"result":"sent_unknown","mode":"image","bytes":2048}'
        payload={'jobId':'02da6474-4738-4b80-bb5d-2a7f97f13ef1','ip':'192.168.180.94',
                 'port':9100,'mode':'image'}
        with patch('app.urllib.request.urlopen',return_value=Response()) as send:
            response=self.client.post('/console/api/admin/reprint',json=payload,
                                      headers={'X-CSRF-Token':token})
        self.assertEqual(200,response.status_code)
        forwarded=json.loads(send.call_args.args[0].data)
        self.assertEqual('image',forwarded['mode'])
        self.assertEqual(20,forwarded['layout']['bodySize'])
        self.assertNotIn('imageBase64',forwarded)
        self.assertLess(len(send.call_args.args[0].data),1024)

    def test_native_reprint_uses_printer_settings_not_bitmap_layout(self):
        self.app.config.update(ADMIN_API_URL='http://printer-api.internal/internal/console',
                               ADMIN_API_KEY='test-api-key')
        self.login()
        token=re.search(r'name="csrf" value="([^"]+)"',self.client.get('/console/').text).group(1)
        class Response:
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def read(self): return b'{"result":"sent_unknown","mode":"native","bytes":240}'
        payload={'jobId':'02da6474-4738-4b80-bb5d-2a7f97f13ef1','ip':'192.168.180.94','port':9100,
                 'mode':'native','nativeSettings':{'font':'A','bodyWidth':2,'bodyHeight':1,'lineSpacingDots':6}}
        with patch('app.urllib.request.urlopen',return_value=Response()) as send:
            response=self.client.post('/console/api/admin/reprint',json=payload,headers={'X-CSRF-Token':token})
        self.assertEqual(200,response.status_code)
        forwarded=json.loads(send.call_args.args[0].data)
        self.assertEqual(2,forwarded['nativeSettings']['bodyWidth'])
        self.assertEqual(6,forwarded['nativeSettings']['lineSpacingDots'])
        self.assertNotIn('layout',forwarded)

    def test_driver_reprint_forwards_layout_without_image_payload(self):
        self.app.config.update(ADMIN_API_URL='http://printer-api.internal/internal/console',
                               ADMIN_API_KEY='test-api-key')
        self.login()
        token=re.search(r'name="csrf" value="([^"]+)"',self.client.get('/console/').text).group(1)
        class Response:
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def read(self): return b'{"result":"sent_unknown","mode":"driver","bytes":2048}'
        payload={'jobId':'02da6474-4738-4b80-bb5d-2a7f97f13ef1','ip':'192.168.180.94',
                 'port':9100,'mode':'driver'}
        with patch('app.urllib.request.urlopen',return_value=Response()) as send:
            response=self.client.post('/console/api/admin/reprint',json=payload,
                                      headers={'X-CSRF-Token':token})
        self.assertEqual(200,response.status_code)
        forwarded=json.loads(send.call_args.args[0].data)
        self.assertEqual('driver',forwarded['mode'])
        self.assertIn('layout',forwarded)
        self.assertNotIn('imageBase64',forwarded)

    def test_reprint_preview_proxies_backend_png_without_transforming_it(self):
        self.app.config.update(ADMIN_API_URL='http://printer-api.internal/internal/console',
                               ADMIN_API_KEY='test-api-key')
        self.login()
        image=b'\x89PNG\r\n\x1a\nmock-png'
        class Response:
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def read(self): return image
        job_id='02da6474-4738-4b80-bb5d-2a7f97f13ef1'
        with patch('app.urllib.request.urlopen',return_value=Response()) as fetch:
            response=self.client.get('/console/api/admin/reprint-preview/'+job_id)
        self.assertEqual(200,response.status_code)
        self.assertEqual('image/png',response.mimetype)
        self.assertEqual(image,response.data)
        preview_query=parse_qs(urlparse(fetch.call_args.args[0].full_url).query)
        self.assertEqual(job_id,preview_query['jobId'][0])
        self.assertEqual('image',preview_query['format'][0])

    def test_reprint_preview_forwards_layout_options(self):
        self.app.config.update(ADMIN_API_URL='http://printer-api.internal/internal/console',
                               ADMIN_API_KEY='test-api-key')
        self.login()
        image=b'\x89PNG\r\n\x1a\nmock-png'
        class Response:
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def read(self): return image
        job_id='02da6474-4738-4b80-bb5d-2a7f97f13ef1'
        layout=json.dumps({'bodySize':22,'bodyBold':False})
        with patch('app.urllib.request.urlopen',return_value=Response()) as fetch:
            response=self.client.get('/console/api/admin/reprint-preview/'+job_id,
                                     query_string={'layout':layout,'includePrintTime':'false'})
        self.assertEqual(200,response.status_code)
        forwarded=json.loads(parse_qs(urlparse(fetch.call_args.args[0].full_url).query)['layout'][0])
        self.assertEqual(22,forwarded['bodySize'])
        self.assertFalse(forwarded['bodyBold'])
        preview_query=parse_qs(urlparse(fetch.call_args.args[0].full_url).query)
        self.assertEqual('false',preview_query['includePrintTime'][0])

    def test_sample_reprint_preview_proxies_backend_png(self):
        self.app.config.update(ADMIN_API_URL='http://printer-api.internal/internal/console',
                               ADMIN_API_KEY='test-api-key')
        self.login()
        image=b'\x89PNG\r\n\x1a\nmock-sample-png'
        class Response:
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def read(self): return image
        layout=json.dumps({'companySize':30,'tableSpaces':7})
        with patch('app.urllib.request.urlopen',return_value=Response()) as fetch:
            response=self.client.get('/console/api/admin/reprint-preview/sample',
                                     query_string={'layout':layout,'includePrintTime':'false'})
        self.assertEqual(200,response.status_code)
        self.assertEqual('image/png',response.mimetype)
        self.assertEqual(image,response.data)
        parsed=parse_qs(urlparse(fetch.call_args.args[0].full_url).query)
        self.assertEqual(30,json.loads(parsed['layout'][0])['companySize'])
        self.assertEqual('image',parsed['format'][0])
        self.assertEqual('false',parsed['includePrintTime'][0])
        self.assertTrue(fetch.call_args.args[0].full_url.startswith(
            'http://printer-api.internal/internal/console/reprint/preview/sample?'))

    def test_native_preview_format_is_forwarded(self):
        self.app.config.update(ADMIN_API_URL='http://printer-api.internal/internal/console',
                               ADMIN_API_KEY='test-api-key')
        self.login()
        class Response:
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def read(self): return b'\x89PNG\r\n\x1a\nmock-native-preview'
        native_layout=json.dumps({'font':'B','bodyWidth':2,'lineSpacingDots':8})
        with patch('app.urllib.request.urlopen',return_value=Response()) as fetch:
            response=self.client.get('/console/api/admin/reprint-preview/sample',
                                     query_string={'format':'native','nativeLayout':native_layout})
        self.assertEqual(200,response.status_code)
        query=parse_qs(urlparse(fetch.call_args.args[0].full_url).query)
        self.assertEqual('native',query['format'][0])
        self.assertEqual('B',json.loads(query['nativeLayout'][0])['font'])

if __name__=='__main__': unittest.main()
