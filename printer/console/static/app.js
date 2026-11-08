'use strict';
const $ = s => document.querySelector(s);
const labels = {accepted:'已接收',queued:'待投递',leased:'已领取',received:'已接收',printing:'打印中',succeeded:'已完成',render_failed:'渲染失败',failed:'失败',expired:'已过期',cancelled:'已取消'};
let page = 1, total = 0, generation = 0;
const date = value => value ? new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(new Date(value)) : '—';
const node = (tag,text,cls) => {const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;return n;};
async function api(path) {
  const response = await fetch('/console/api/'+path,{credentials:'same-origin',cache:'no-store'});
  if(response.status===401){location.assign('/console/login');throw Error('请重新登录');}
  const result = await response.json();if(!response.ok)throw Error(result.error || '请求失败，请重试');return result;
}
async function load() {
  const serial=++generation;
  const params = new URLSearchParams(new FormData($('#filters')));params.set('page',page);
  $('#error').hidden=true;
  try {
    const data=await api('jobs?'+params);if(serial!==generation)return;
    total=data.total;$('#total').textContent=total.toLocaleString();$('#rows').replaceChildren();
    for(const job of data.rows){
      const tr=node('tr');tr.append(node('td',date(job.received_at)));
      const invoice=node('td',job.invoice_no || '未提供单号');invoice.append(node('small',job.id));tr.append(invoice);
      tr.append(node('td',job.customer || '—'),node('td',job.source_ip || '未记录（历史数据）'));
      const status=node('td');status.append(node('span',labels[job.status] || job.status,'badge '+job.status));tr.append(status);
      tr.append(node('td',job.artifact_size==null?'未生成':job.artifact_size.toLocaleString()+' B'));
      const action=node('td'),button=node('button','查看');button.addEventListener('click',()=>detail(job.id));action.append(button);tr.append(action);$('#rows').append(tr);
    }
    if(!data.rows.length){const row=node('tr'),td=node('td','没有符合条件的记录','empty');td.colSpan=7;row.append(td);$('#rows').append(row);}
    $('#page-info').textContent=`第 ${page} / ${Math.max(1,Math.ceil(total/30))} 页 · 共 ${total} 条`;
    $('#previous').disabled=page<=1;$('#next').disabled=page*30>=total;
  }catch(error){if(serial===generation){$('#error').textContent=error.message;$('#error').hidden=false;}}
}
async function detail(id){
  const dialog=$('#detail');if(!dialog.open)dialog.showModal();$('#detail-message').textContent='正在读取…';$('#metadata').replaceChildren();$('#raw').textContent='';$('#parsed').textContent='';$('#job-error').textContent='';
  dialog.dataset.id=id;
  try{
    const job=await api('jobs/'+id);if(dialog.dataset.id!==id)return;
    $('#detail-message').textContent=job.raw_truncated?'原文过长，仅显示前 200,000 字符。':'';
    const fields=[['记录 ID',job.id],['接收时间',date(job.received_at)],['来源 IP',job.source_ip || '历史记录未采集'],['来源类型',job.source],['编码',job.payload_encoding],['任务状态',labels[job.status] || job.status],['渲染状态',job.render_status],['投递状态',job.delivery_status],['设备',job.target_device_id || '未绑定'],['产物大小',job.artifact_size==null?'—':job.artifact_size+' B'],['SHA-256',job.artifact_sha256 || '—']];
    for(const [key,value] of fields)$('#metadata').append(node('dt',key),node('dd',value));
    $('#raw').textContent=job.raw_payload || '';$('#parsed').textContent=JSON.stringify(job.parsed_payload,null,2);$('#job-error').textContent=[job.error_code,job.error_message].filter(Boolean).join('：');
  }catch(error){$('#detail-message').textContent=error.message;}
}
$('#filters').addEventListener('submit',e=>{e.preventDefault();page=1;load();});
$('#filters').addEventListener('reset',()=>{page=1;setTimeout(load,0);});
$('#refresh').addEventListener('click',load);$('#previous').addEventListener('click',()=>{if(page>1){page--;load();}});$('#next').addEventListener('click',()=>{if(page*30<total){page++;load();}});$('#close-detail').addEventListener('click',()=>$('#detail').close());load();
