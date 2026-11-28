'use strict';
const $ = s => document.querySelector(s);
const node = (tag,text,cls) => {const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;return n;};
const labels = {accepted:'已接收',queued:'待投递',leased:'已领取',received:'已接收',printing:'打印中',succeeded:'已完成',discarded:'已废弃',render_failed:'渲染失败',failed:'失败',expired:'已过期',cancelled:'已取消',sent_unknown:'已发送，结果未知',delivery_unknown:'投递结果未知'};
let page = 1, total = 0, generation = 0, discardedPage = 1, discardedTotal = 0, discardedGeneration = 0;
let previewPromise = Promise.resolve(false), previewUrl = '';
let layoutPreviewGeneration = 0, layoutPreviewTimer = 0, previewRecordId = '';
const layoutStorageKey='printerReceiptLayoutsV1';
const nativeLayoutMigrationKey='printerNativeReceiptLayoutV3';
const defaultLayout={companySize:30,titleSize:38,bodySize:26,totalSize:30,tableSpaces:10,lineSpacing:4,companyBold:false,titleBold:true,bodyBold:false,totalBold:false,companyAlign:'center',titleAlign:'center',bodyAlign:'left',totalAlign:'left'};
const defaultNative={font:'A',lineSpacingDots:16,tableSpaces:3,blocks:defaultNativeBlocks()};
const sectionNames={company:'店名',title:'单据标题',body:'正文',table:'商品表格',total:'金额合计',address:'地址'};
const layoutBounds={companySize:[18,48],titleSize:[18,56],bodySize:[14,36],totalSize:[14,48],tableSpaces:[1,20],lineSpacing:[0,20]};
function normalizeLayout(value){
  const layout={...defaultLayout};if(!value||typeof value!=='object')return layout;
  for(const [key,[min,max]] of Object.entries(layoutBounds)){const number=Number(value[key]);if(Number.isInteger(number)&&number>=min&&number<=max)layout[key]=number;}
  for(const key of ['companyBold','titleBold','bodyBold','totalBold'])if(typeof value[key]==='boolean')layout[key]=value[key];
  for(const key of ['companyAlign','titleAlign','bodyAlign','totalAlign'])if(['left','center','right'].includes(value[key]))layout[key]=value[key];
  return layout;
}
function normalizeNative(value){
  const settings={...defaultNative};if(!value||typeof value!=='object')return {...settings,blocks:defaultNativeBlocks()};
  if(['A','B'].includes(value.font))settings.font=value.font;
  for(const [key,min,max] of [['lineSpacingDots',0,40],['tableSpaces',1,16]]){const n=Number(value[key]);if(Number.isInteger(n)&&n>=min&&n<=max)settings[key]=n;}
  settings.blocks=normalizeNativeBlocks(value.blocks,value);
  return settings;
}
function defaultNativeBlocks(legacy={}){
  const defs=[['company',1,1,false,'center'],['title',2,1,true,'center'],['body',1,1,false,'left'],['rule',1,1,false,'left'],['table',1,1,false,'left'],['rule',1,1,false,'left'],['total',1,1,true,'left'],['address',1,1,false,'left']];
  return defs.map(([section,width,height,bold,align])=>section==='rule'?{type:'rule',section:'',gapDots:0,feedDots:0,width,height,bold,align}:{type:'section',section,gapDots:0,feedDots:0,width:Number(legacy[`${section==='table'?'body':section}Width`]||width),height:Number(legacy[`${section==='table'?'body':section}Height`]||height),bold:typeof legacy[`${section==='table'?'body':section}Bold`]==='boolean'?legacy[`${section==='table'?'body':section}Bold`]:bold,align:legacy[`${section==='table'?'body':section}Align`]||align});
}
function normalizeNativeBlocks(blocks,legacy={}){
  if(!Array.isArray(blocks))return defaultNativeBlocks(legacy);
  return blocks.slice(0,32).map(block=>{
    const type=['section','rule','feed','text'].includes(block?.type)?block.type:'section';
    const section=type==='section'&&Object.hasOwn(sectionNames,block?.section)?block.section:'';
    const bounded=(value,fallback,max)=>Number.isInteger(Number(value))?Math.max(0,Math.min(max,Number(value))):fallback;
    return {type,section,text:String(block?.text||'').replace(/[\r\n]/g,' ').slice(0,256),gapDots:bounded(block?.gapDots,0,120),feedDots:bounded(block?.feedDots,8,120),width:Math.max(1,Math.min(8,Number(block?.width)||1)),height:Math.max(1,Math.min(8,Number(block?.height)||1)),bold:!!block?.bold,align:['left','center','right'].includes(block?.align)?block.align:'left'};
  });
}
function updateReceiptNativeLayout(value){
  const settings=normalizeNative(value);settings.lineSpacingDots=16;
  settings.blocks=settings.blocks.filter(block=>!(block.type==='section'&&block.section==='address'));
  settings.blocks=settings.blocks.map(block=>block.type==='section'&&block.section==='company'?{...block,width:1,height:1}:block.type==='section'&&block.section==='title'?{...block,width:2,height:1,bold:true}:block);
  const address={type:'section',section:'address',text:'',gapDots:0,feedDots:0,width:1,height:1,bold:false,align:'left'};
  const totalIndex=settings.blocks.findIndex(block=>block.type==='section'&&block.section==='total');
  settings.blocks.splice(totalIndex<0?settings.blocks.length:totalIndex+1,0,address);
  return settings;
}
function loadProfiles(){
  try{
    const stored=JSON.parse(localStorage.getItem(layoutStorageKey)||'null');
    if(stored&&Array.isArray(stored.profiles)&&stored.profiles.length){
      const profiles=stored.profiles.map(profile=>({id:String(profile.id),name:String(profile.name||'未命名方案').trim(),layout:normalizeLayout(profile.layout),nativeSettings:normalizeNative(profile.nativeSettings),format:profile.format==='driver'?'driver':'image'}));
      if(localStorage.getItem(nativeLayoutMigrationKey)!=='done'){
        const defaultProfile=profiles.find(profile=>profile.name==='默认方案');
        if(defaultProfile)defaultProfile.nativeSettings=normalizeNative(defaultNative);
        const active=profiles.find(profile=>profile.id===String(stored.activeId));
        if(active&&active.name!=='默认方案')active.nativeSettings=updateReceiptNativeLayout(active.nativeSettings);
        localStorage.setItem(nativeLayoutMigrationKey,'done');
      }
      const activeName=profiles.find(profile=>profile.id===stored.activeId)?.name;
      const unique=[];
      profiles.forEach(profile=>{const name=profile.name.toLocaleLowerCase();const index=unique.findIndex(item=>item.name.toLocaleLowerCase()===name);if(index<0)unique.push(profile);else unique[index]=profile;});
      return {activeId:unique.find(profile=>profile.name===activeName)?.id||unique[0].id,profiles:unique};
    }
    const legacy=JSON.parse(localStorage.getItem('printerReceiptLayoutV1')||'null');
    return {activeId:'default',profiles:[{id:'default',name:'默认方案',layout:normalizeLayout(legacy||defaultLayout),nativeSettings:{...defaultNative},format:'image'}]};
  }catch{return {activeId:'default',profiles:[{id:'default',name:'默认方案',layout:{...defaultLayout},nativeSettings:{...defaultNative},format:'image'}]};}
}
let layoutProfiles=loadProfiles();
function persistProfiles(){localStorage.setItem(layoutStorageKey,JSON.stringify(layoutProfiles));}
function activeProfile(){return layoutProfiles.profiles.find(profile=>profile.id===layoutProfiles.activeId)||layoutProfiles.profiles[0];}
function savedLayout(){return activeProfile()?.layout||{...defaultLayout};}
function updateProfileSelectors(){
  for(const selector of [$('#layout-profile'),$('#reprint-profile')]){
    const prior=selector.value;selector.replaceChildren();
    layoutProfiles.profiles.forEach(profile=>selector.append(new Option(profile.name,profile.id)));
    selector.value=layoutProfiles.profiles.some(profile=>profile.id===prior)?prior:layoutProfiles.activeId;
  }
  $('#layout-name').value=activeProfile()?.name||'';
}
function currentLayout(){
  const layout={};document.querySelectorAll('#layout-controls [data-layout]').forEach(input=>{
    layout[input.dataset.layout]=input.type==='checkbox'?input.checked:input.type==='number'?Number(input.value):input.value;
  });return normalizeLayout(layout);
}
function fillLayoutControls(layout){document.querySelectorAll('#layout-controls [data-layout]').forEach(input=>{const value=layout[input.dataset.layout];if(input.type==='checkbox')input.checked=value;else input.value=value;});}
function buildNativeControls(){
  const form=document.createElement('form');form.id='native-controls';form.addEventListener('submit',event=>event.preventDefault());
  const grid=document.createElement('div');grid.className='layout-grid';
  const add=(label,key,type='number',min=1,max=8,options=null)=>{const wrap=document.createElement('label');wrap.append(document.createTextNode(label));let input;
    if(options){input=document.createElement('select');for(const [value,text] of options)input.add(new Option(text,value));}
    else{input=document.createElement('input');input.type=type;if(type==='number'){input.min=min;input.max=max;input.step=1;}}
    input.dataset.native=key;wrap.append(input);grid.append(wrap);};
  add('打印机字体','font','select',0,0,[['A','Font A'],['B','Font B']]);
  add('表格列空格数','tableSpaces','number',1,16);
  add('行距（点）','lineSpacingDots','number',0,40);
  form.append(grid);
  const heading=node('div','小票区块 · 72 mm / 576 点有效宽度','section-head');heading.className='section-head native-block-heading';form.append(heading);
  const list=document.createElement('div');list.id='native-block-list';form.append(list);
  const actions=document.createElement('div');actions.className='native-block-actions';
  for(const [action,label] of [['text','添加文字'],['rule','添加分割线'],['feed','添加留白']]){const button=node('button',label);button.type='button';button.dataset.addBlock=action;actions.append(button);}
  form.append(actions);$('#layout-controls').after(form);
  form.addEventListener('input',event=>{if(event.target.matches('[data-native], [data-block-field]'))saveLayoutAndPreview();});
  form.addEventListener('change',event=>{if(event.target.matches('[data-native], [data-block-field]'))saveLayoutAndPreview();});
  form.addEventListener('click',event=>{
    const addButton=event.target.closest('[data-add-block]');
    if(addButton){const blocks=currentNative().blocks,type=addButton.dataset.addBlock;blocks.push({type,section:'',text:type==='text'?'自定义文字':'',gapDots:0,feedDots:type==='feed'?8:0,width:1,height:1,bold:false,align:'left'});renderNativeBlocks(blocks);saveLayoutAndPreview();return;}
    const button=event.target.closest('[data-block-action]');if(!button)return;
    const blocks=currentNative().blocks,index=Number(button.dataset.index),action=button.dataset.blockAction;
    if(action==='up'&&index>0)[blocks[index-1],blocks[index]]=[blocks[index],blocks[index-1]];
    if(action==='down'&&index<blocks.length-1)[blocks[index+1],blocks[index]]=[blocks[index],blocks[index+1]];
    if(action==='remove')blocks.splice(index,1);
    renderNativeBlocks(blocks);saveLayoutAndPreview();
  });
  renderNativeBlocks(defaultNative.blocks);
}
buildNativeControls();
function renderNativeBlocks(blocks){
  const list=$('#native-block-list');if(!list)return;list.replaceChildren();
  blocks.forEach((block,index)=>{
    const item=document.createElement('article');item.className='native-block';item.dataset.index=index;item.dataset.type=block.type;item.dataset.section=block.section;
    const title=block.type==='rule'?'分割线':block.type==='feed'?'留白':block.type==='text'?'自定义文字':sectionNames[block.section]||'区块';
    const header=document.createElement('div');header.className='native-block-head';header.append(node('strong',`${index+1}. ${title}`));
    const move=document.createElement('div');move.className='native-block-move';
    for(const [action,label,disabled] of [['up','上移',index===0],['down','下移',index===blocks.length-1],['remove','删除',blocks.length===1||block.type==='section']]){const button=node('button',label);button.type='button';button.dataset.blockAction=action;button.dataset.index=index;button.disabled=disabled;move.append(button);}
    header.append(move);item.append(header);
    if(block.type==='section'||block.type==='rule'||block.type==='text')item.append(blockInput('与上一区块间隔（点）','gapDots',block.gapDots,0,120,index));
    if(block.type==='feed')item.append(blockInput('留白高度（点）','feedDots',block.feedDots,1,120,index));
    if(block.type==='text'){
      const text=document.createElement('input');text.type='text';text.maxLength=256;text.value=block.text;text.dataset.blockField='text';item.append(text);
    }
    if(block.type==='section'||block.type==='text'){
      const details=document.createElement('details'),summary=node('summary','字体与对齐');details.append(summary);
      const style=document.createElement('div');style.className='native-block-style';
      style.append(blockInput('宽度倍数','width',block.width,1,8,index),blockInput('高度倍数','height',block.height,1,8,index));
      const align=document.createElement('label');align.append(document.createTextNode('对齐'));const select=document.createElement('select');select.dataset.blockField='align';select.dataset.index=index;for(const [value,label] of [['left','左对齐'],['center','居中'],['right','右对齐']])select.add(new Option(label,value));select.value=block.align;align.append(select);style.append(align);
      const bold=document.createElement('label'),check=document.createElement('input');check.type='checkbox';check.dataset.blockField='bold';check.dataset.index=index;check.checked=block.bold;bold.append(check,document.createTextNode('加粗'));style.append(bold);
      details.append(style);item.append(details);
    }
    list.append(item);
  });
}
function blockInput(label,key,value,min,max,index){const wrap=document.createElement('label');wrap.append(document.createTextNode(label));const input=document.createElement('input');input.type='number';input.min=min;input.max=max;input.step=1;input.value=value;input.dataset.blockField=key;input.dataset.index=index;wrap.append(input);return wrap;}
function currentNative(){const value={};document.querySelectorAll('#native-controls [data-native]').forEach(input=>{value[input.dataset.native]=input.type==='checkbox'?input.checked:input.type==='number'?Number(input.value):input.value;});value.blocks=[...$('#native-block-list').children].map(item=>{const block={...defaultNative.blocks[0],type:item.dataset.type,section:item.dataset.section||'',text:''};item.querySelectorAll('[data-block-field]').forEach(input=>{const key=input.dataset.blockField;block[key]=input.type==='checkbox'?input.checked:(input.type==='number'?Number(input.value):input.value);});return block;});return normalizeNative(value);}
function fillNativeControls(settings){document.querySelectorAll('#native-controls [data-native]').forEach(input=>{const value=settings[input.dataset.native];if(input.type==='checkbox')input.checked=value;else input.value=value;});renderNativeBlocks(settings.blocks);}
function applyEditorFormat(format){const driver=format==='driver';$('#layout-controls').hidden=false;$('#native-controls').hidden=true;$('#receipt-editor .section-head .muted').textContent=driver?'驱动渲染排版':'位图排版';$('#image-preview-wrap .muted').textContent=driver?'预览为打印机驱动接收的电脑字体点阵；实际纸面效果仍需实机校准。':'预览由服务端按位图方案生成。';}
function previewLayout(id,layout,format=$('#layout-format').value,nativeSettings=currentNative()){
  const serial=++layoutPreviewGeneration;
  previewPromise=loadImagePreview(id,layout,format,nativeSettings,format==='image'||format==='driver'?$('#include-print-time').checked:true).then(url=>{
    if(serial!==layoutPreviewGeneration||previewRecordId!==id){URL.revokeObjectURL(url);return false;}
    if(previewUrl)URL.revokeObjectURL(previewUrl);previewUrl=url;
    const preview=$('#image-preview');preview.src=url;preview.dataset.loaded='true';$('#image-preview-wrap').hidden=false;
    const detailPreview=$('#detail-image-preview');detailPreview.src=url;detailPreview.dataset.loaded='true';$('#detail-preview-status').textContent=format==='driver'?'预览为打印机驱动接收的排版图；实际纸面效果仍需实机确认。':'';
    $('#detail-image-preview-wrap').hidden=!['image','driver'].includes(document.querySelector('[name="reprint-mode"]:checked').value);return true;
  }).catch(error=>{
    if(serial===layoutPreviewGeneration&&previewRecordId===id){$('#layout-status').textContent=error.message;$('#detail-preview-status').textContent=error.message;}
    return false;
  });
}
function saveLayoutAndPreview(){
  const layout=currentLayout();$('#layout-status').textContent='有未保存修改；预览使用当前编辑内容。';
  if(previewRecordId){clearTimeout(layoutPreviewTimer);layoutPreviewTimer=setTimeout(()=>previewLayout(previewRecordId,layout,$('#layout-format').value),100);}
}
const date = value => value ? new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(new Date(value)) : '—';
async function api(path) {
  const response = await fetch('/console/api/'+path,{credentials:'same-origin',cache:'no-store'});
  if(response.status===401){location.assign('/console/login');throw Error('请重新登录');}
  const result = await response.json();if(!response.ok)throw Error(result.error || '请求失败，请重试');return result;
}
async function loadDeviceStatus(){
  const list=$('#device-status-list'),checked=$('#device-status-checked'),button=$('#refresh-device-status');
  button.disabled=true;
  try{
    const data=await api('devices/status');list.replaceChildren();
    for(const device of data.devices||[]){
      const online=!!device.online,busy=online&&!device.available;
      const item=node('div',null,`device-status-item ${online?(busy?'busy':'online'):'offline'}`);
      item.append(node('span',device.deviceId,'device-status-name'));
      const state=node('span',null,'device-status-state');state.append(node('i',null,'device-status-dot'));
      state.append(document.createTextNode(!online?'离线':busy?'在线 · 通道占用':'在线'));
      item.append(state);list.append(item);
    }
    checked.textContent=`状态更新时间：${date(data.checkedAt)}`;
  }catch(error){
    list.replaceChildren(node('p',error.message,'muted'));
    checked.textContent='';
  }finally{button.disabled=false;}
}
async function action(path,payload) {
  const response=await fetch('/console/api/admin/'+path,{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json','X-CSRF-Token':document.querySelector('#printer-scan [name=csrf]').value},body:JSON.stringify(payload)});
  if(response.status===401){location.assign('/console/login');throw Error('请重新登录');}
  const result=await response.json();if(!response.ok)throw Error(result.error||'操作失败');return result;
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
async function loadDiscarded() {
  const serial=++discardedGeneration;
  $('#discarded-error').hidden=true;
  try {
    const data=await api('jobs/discarded?page='+discardedPage);if(serial!==discardedGeneration)return;
    discardedTotal=data.today_total;$('#discarded-today').textContent=discardedTotal.toLocaleString();$('#discarded-rows').replaceChildren();
    for(const job of data.rows){
      const tr=node('tr');tr.append(node('td',date(job.received_at)),node('td',job.source_ip||'未记录'));
      tr.append(node('td',job.error_message||'非法格式'));
      const preview=node('td',job.raw_preview||'（空报文）','raw-preview');preview.title='点击查看完整报文';preview.tabIndex=0;
      preview.addEventListener('click',()=>detail(job.id));preview.addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();detail(job.id);}});tr.append(preview);$('#discarded-rows').append(tr);
    }
    if(!data.rows.length){const row=node('tr'),cell=node('td','今天没有废弃报文','empty');cell.colSpan=4;row.append(cell);$('#discarded-rows').append(row);}
    const pages=Math.max(1,Math.ceil(data.today_total/30));$('#discarded-page-info').textContent=`第 ${discardedPage} / ${pages} 页 · 今日 ${data.today_total} 条`;
    $('#discarded-previous').disabled=discardedPage<=1;$('#discarded-next').disabled=discardedPage*30>=data.today_total;
  }catch(error){if(serial===discardedGeneration){$('#discarded-error').textContent=error.message;$('#discarded-error').hidden=false;}}
}
async function detail(id){
  const dialog=$('#detail');if(!dialog.open)dialog.showModal();$('#detail-message').textContent='正在读取…';$('#metadata').replaceChildren();$('#raw').textContent='';$('#parsed').textContent='';$('#job-error').textContent='';$('#reprint').disabled=true;
  dialog.dataset.id=id;previewRecordId=id;$('#preview-record').textContent=`（记录 ${id}）`;$('#layout-profile').value=layoutProfiles.activeId;$('#reprint-profile').value=layoutProfiles.activeId;$('#reprint-profile-wrap').hidden=!['image','driver'].includes(document.querySelector('[name="reprint-mode"]:checked').value);$('#include-print-time-wrap').hidden=!['image','driver'].includes(document.querySelector('[name="reprint-mode"]:checked').value);fillLayoutControls(savedLayout());fillNativeControls(activeProfile()?.nativeSettings||defaultNative);$('#layout-status').textContent='正在载入排版预览…';
  $('#image-preview').removeAttribute('src');$('#image-preview').dataset.loaded='';$('#image-preview-wrap').hidden=true;
  $('#detail-image-preview').removeAttribute('src');$('#detail-image-preview').dataset.loaded='';$('#detail-image-preview-wrap').hidden=true;$('#detail-preview-status').textContent='正在生成预览…';
  if(previewUrl){URL.revokeObjectURL(previewUrl);previewUrl='';}
  try{
    const job=await api('jobs/'+id);if(dialog.dataset.id!==id)return;
    $('#detail-message').textContent=job.raw_truncated?'原文过长，仅显示前 200,000 字符。':'';
    const fields=[['记录 ID',job.id],['接收时间',date(job.received_at)],['来源 IP',job.source_ip || '历史记录未采集'],['来源类型',job.source],['编码',job.payload_encoding],['任务状态',labels[job.status] || job.status],['渲染状态',job.render_status],['投递状态',job.delivery_status],['设备',job.target_device_id || '未绑定'],['产物大小',job.artifact_size==null?'—':job.artifact_size+' B'],['SHA-256',job.artifact_sha256 || '—']];
    for(const [key,value] of fields)$('#metadata').append(node('dt',key),node('dd',value));
    $('#reprint').disabled=false;$('#reprint').dataset.jobId=id;
    if(!job.raw_payload)$('#detail-message').textContent='这条记录没有原文，点击重打后会显示具体错误。';
    $('#raw').textContent=job.raw_payload || '';$('#parsed').textContent=JSON.stringify(job.parsed_payload,null,2);$('#job-error').textContent=[job.error_code,job.error_message].filter(Boolean).join('：');
    previewLayout(id,currentLayout(),$('#layout-format').value,currentNative());
  }catch(error){$('#detail-message').textContent=error.message;}
}
async function loadImagePreview(id,layout,format='image',nativeSettings=currentNative(),includePrintTime=true){
  const query=new URLSearchParams({format});
  query.set('layout',JSON.stringify(layout));
  query.set('includePrintTime',String(includePrintTime));
  const endpoint=id==='sample'?'/console/api/admin/reprint-preview/sample':`/console/api/admin/reprint-preview/${id}`;
  const response=await fetch(`${endpoint}?${query}`,{credentials:'same-origin',cache:'no-store'});
  if(response.status===401){location.assign('/console/login');throw Error('请重新登录');}
  if(!response.ok){let error={};try{error=await response.json();}catch{}throw Error(error.error||'打印图片预览暂不可用。');}
  return URL.createObjectURL(await response.blob());
}
$('#printer-scan').addEventListener('submit',async e=>{
  e.preventDefault();const button=e.submitter;button.disabled=true;$('#action-message').textContent='正在通过 ESP8266 探测网段，扫描可能需要几分钟。';$('#scan-results').replaceChildren();
  try{let result=await action('scan',{cidr:new FormData(e.currentTarget).get('cidr')});while(result.status==='running'){ $('#action-message').textContent=`正在检查 ${result.current}（${result.checked}/${result.total}）…`;await new Promise(resolve=>setTimeout(resolve,1200));result=await api('admin/scan/'+result.scanId); }if(result.status!=='completed')throw Error(result.error||'扫描未完成');$('#action-message').textContent=`扫描结束，发现 ${result.targets.length} 个开放服务候选。开放端口不能单独确认设备类型。`;
    for(const target of result.targets){const item=node('div',null,'scan-target');item.append(node('span',`${target.ip}:${target.port}`));if(target.port===9100){const choose=node('button','设为重打目标');choose.addEventListener('click',()=>{$('#target-ip').value=target.ip;$('#action-message').textContent=`已选择 ${target.ip}，打开一条已接收记录后可手动重打。`;});const test=node('button','发送测试单');test.addEventListener('click',async()=>{if(!confirm(`将向 ${target.ip} 发送测试单并实际打印，继续吗？`))return;test.disabled=true;try{const sent=await action('test-print',target);$('#action-message').textContent=`测试打印数据已发送（${sent.bytes} 字节）；打印机未提供纸面确认。`;}catch(error){$('#action-message').textContent=error.message;}finally{test.disabled=false;}});item.append(choose,test);}$('#scan-results').append(item);}
    if(!result.targets.length)$('#scan-results').append(node('span','没有发现开放的打印服务。','muted'));
  }catch(error){$('#action-message').textContent=error.message;}finally{button.disabled=false;}
});
$('#printer-test').addEventListener('submit',async e=>{
  e.preventDefault();const button=e.submitter,ip=new FormData(e.currentTarget).get('ip').trim();
  if(!/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)){ $('#test-print-message').textContent='请输入有效的 IPv4 地址。';return; }
  if(!confirm(`将通过当前网关向 ${ip}:9100 发送测试单并实际出纸，继续吗？`))return;
  button.disabled=true;$('#test-print-message').textContent='正在发送测试单…';
  try{const result=await action('test-print',{ip,port:9100});$('#test-print-message').textContent=`测试打印数据已发送（${result.bytes} 字节）；打印机未提供纸面确认。`;}
  catch(error){$('#test-print-message').textContent=error.message;}
  finally{button.disabled=false;}
});
$('#query-printer-status').addEventListener('click',async e=>{
  const button=e.currentTarget,ip=$('#printer-test [name=ip]').value.trim();
  if(!/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)){ $('#printer-status-result').textContent='请输入有效的 IPv4 地址。';return; }
  button.disabled=true;$('#printer-status-result').textContent='正在查询 ESC/POS 状态…';
  try{
    const result=await action('printer-status',{ip,port:9100});
    const bytes=result.bytes.length?result.bytes.join(' '):'无响应';
    $('#printer-status-result').textContent=`收到 ${result.bytesReceived} 字节：${bytes}。这是打印机状态查询响应，不代表某张单据已打印完成。`;
  }catch(error){$('#printer-status-result').textContent=error.message;}
  finally{button.disabled=false;}
});
$('#reprint').addEventListener('click',async()=>{
  const ip=$('#target-ip').value.trim(),jobId=$('#reprint').dataset.jobId,selected=document.querySelector('[name="reprint-mode"]:checked');
  if(!ip||!jobId){$('#detail-message').textContent='请填写打印机 IP。';return;}
  if(!selected){$('#detail-message').textContent='请选择重打格式。';return;}
  const mode=selected.value;
  const description=mode==='image'?'按整张位图':mode==='driver'?'按电脑字体与打印机驱动渲染':'按原生纯文字';
  if(!confirm(`将${description}重新打印整份单据，可能产生重复纸件，确认继续？`))return;
  $('#reprint').disabled=true;$('#detail-message').textContent=`正在${description}发送打印数据…`;
  try{
    const selectedProfile=layoutProfiles.profiles.find(profile=>profile.id===$('#reprint-profile').value)||activeProfile();
    const payload={jobId,ip,port:9100,mode,includePrintTime:$('#include-print-time').checked};if(mode==='image'||mode==='driver'){
      if(mode==='image'||mode==='driver')payload.layout=selectedProfile.layout;
      previewLayout(jobId,payload.layout,mode,payload.nativeSettings||currentNative());
      if(!(await previewPromise))throw Error('当前记录没有可用的图片预览。');
    }
    const result=await action('reprint',payload);
    $('#detail-message').textContent=`${description}数据已发送（${result.bytes} 字节）；打印机未提供纸面确认。`;
    $('#action-message').textContent=`已请求重打任务 ${jobId}。`;
  }catch(error){$('#detail-message').textContent=`重打失败：${error.message}`;}
  finally{$('#reprint').disabled=false;}
});
$('#include-print-time').addEventListener('change',()=>{if(previewRecordId){const profile=layoutProfiles.profiles.find(item=>item.id===$('#reprint-profile').value)||activeProfile();const mode=document.querySelector('[name="reprint-mode"]:checked').value;if(['image','driver'].includes(mode))previewLayout(previewRecordId,profile.layout,mode,profile.nativeSettings);}});
document.querySelectorAll('[name="reprint-mode"]').forEach(input=>input.addEventListener('change',()=>{
  const formatted=['image','driver'].includes(input.value);$('#reprint-profile-wrap').hidden=!formatted;$('#detail-image-preview-wrap').hidden=!formatted;$('#include-print-time-wrap').hidden=!['image','driver'].includes(input.value);
  if(formatted&&previewRecordId){const profile=layoutProfiles.profiles.find(item=>item.id===$('#reprint-profile').value)||activeProfile();fillLayoutControls(profile.layout);fillNativeControls(profile.nativeSettings);$('#layout-format').value=input.value;applyEditorFormat(input.value);previewLayout(previewRecordId,profile.layout,input.value,profile.nativeSettings);}
}));
$('#layout-controls').addEventListener('input',saveLayoutAndPreview);
$('#layout-controls').addEventListener('change',saveLayoutAndPreview);
$('#layout-controls').addEventListener('submit',event=>event.preventDefault());
$('#layout-format').addEventListener('change',()=>{applyEditorFormat($('#layout-format').value);if(previewRecordId)previewLayout(previewRecordId,currentLayout(),$('#layout-format').value,currentNative());});
$('#layout-profile').addEventListener('change',()=>{
  const profile=layoutProfiles.profiles.find(item=>item.id===$('#layout-profile').value);if(!profile)return;
  layoutProfiles.activeId=profile.id;persistProfiles();$('#reprint-profile').value=profile.id;fillLayoutControls(profile.layout);fillNativeControls(profile.nativeSettings);$('#layout-format').value=profile.format||'image';applyEditorFormat(profile.format||'image');$('#layout-status').textContent=`已载入方案「${profile.name}」。`;
  if(previewRecordId)previewLayout(previewRecordId,profile.layout,profile.format||'image',profile.nativeSettings);
});
$('#reprint-profile').addEventListener('change',()=>{
  const profile=layoutProfiles.profiles.find(item=>item.id===$('#reprint-profile').value);if(profile){$('#layout-profile').value=profile.id;layoutProfiles.activeId=profile.id;persistProfiles();fillLayoutControls(profile.layout);fillNativeControls(profile.nativeSettings);$('#layout-format').value=profile.format||'image';applyEditorFormat(profile.format||'image');const mode=document.querySelector(`[name="reprint-mode"][value="${profile.format||'image'}"]`);if(mode)mode.checked=true;if(previewRecordId)previewLayout(previewRecordId,profile.layout,profile.format||'image',profile.nativeSettings);}
});
$('#layout-save').addEventListener('click',()=>{
  const name=$('#layout-name').value.trim();if(!name){$('#layout-status').textContent='请先填写方案名称。';$('#layout-name').focus();return;}
  const existing=layoutProfiles.profiles.find(profile=>profile.name.toLocaleLowerCase()===name.toLocaleLowerCase());
  const profile=existing||{id:crypto.randomUUID(),name,layout:currentLayout(),nativeSettings:currentNative(),format:'image'};profile.name=name;profile.layout=currentLayout();profile.nativeSettings=currentNative();profile.format=$('#layout-format').value;
  if(!existing)layoutProfiles.profiles.push(profile);layoutProfiles.activeId=profile.id;persistProfiles();updateProfileSelectors();$('#layout-profile').value=profile.id;$('#reprint-profile').value=profile.id;$('#layout-status').textContent=existing?`已用当前设置覆盖同名方案「${name}」。`:`方案「${name}」已保存到此浏览器。`;
});
$('#layout-update').addEventListener('click',()=>{
  const profile=activeProfile();if(!profile)return;const name=$('#layout-name').value.trim();if(!name){$('#layout-status').textContent='请先填写方案名称。';return;}
  const collision=layoutProfiles.profiles.find(item=>item.id!==profile.id&&item.name.toLocaleLowerCase()===name.toLocaleLowerCase());
  if(collision){collision.layout=currentLayout();collision.nativeSettings=currentNative();collision.format=$('#layout-format').value;layoutProfiles.profiles=layoutProfiles.profiles.filter(item=>item.id!==profile.id);layoutProfiles.activeId=collision.id;}
  else{profile.name=name;profile.layout=currentLayout();profile.nativeSettings=currentNative();profile.format=$('#layout-format').value;}
  persistProfiles();updateProfileSelectors();$('#layout-profile').value=layoutProfiles.activeId;$('#reprint-profile').value=layoutProfiles.activeId;$('#layout-status').textContent=`方案「${name}」已覆盖保存。`;
});
$('#layout-delete').addEventListener('click',()=>{
  if(layoutProfiles.profiles.length<=1){$('#layout-status').textContent='至少保留一个排版方案。';return;}
  const removed=activeProfile();layoutProfiles.profiles=layoutProfiles.profiles.filter(profile=>profile.id!==removed.id);layoutProfiles.activeId=layoutProfiles.profiles[0].id;persistProfiles();updateProfileSelectors();fillLayoutControls(savedLayout());fillNativeControls(activeProfile().nativeSettings);$('#layout-status').textContent=`方案「${removed.name}」已删除。`;if(previewRecordId)previewLayout(previewRecordId,savedLayout(),activeProfile().format,activeProfile().nativeSettings);
});
$('#layout-reset').addEventListener('click',()=>{fillLayoutControls(defaultLayout);$('#layout-status').textContent='已载入当前格式的默认参数；点击“另存方案”或“覆盖保存”以保存。';saveLayoutAndPreview();});
$('#filters').addEventListener('submit',e=>{e.preventDefault();page=1;load();});
$('#filters').addEventListener('reset',()=>{page=1;setTimeout(load,0);});
$('#refresh').addEventListener('click',()=>{load();loadDiscarded();});$('#refresh-discarded').addEventListener('click',loadDiscarded);$('#discarded-previous').addEventListener('click',()=>{if(discardedPage>1){discardedPage--;loadDiscarded();}});$('#discarded-next').addEventListener('click',()=>{if(discardedPage*30<discardedTotal){discardedPage++;loadDiscarded();}});$('#previous').addEventListener('click',()=>{if(page>1){page--;load();}});$('#next').addEventListener('click',()=>{if(page*30<total){page++;load();}});$('#close-detail').addEventListener('click',()=>$('#detail').close());
$('#open-layout-editor').addEventListener('click',()=>{
  $('#layout-editor').showModal();
  const profile=activeProfile();$('#layout-format').value=profile.format||'image';fillLayoutControls(profile.layout);fillNativeControls(profile.nativeSettings);applyEditorFormat(profile.format||'image');
  if(!previewRecordId){previewRecordId='sample';$('#preview-record').textContent='（示例报文）';}
  previewLayout(previewRecordId,currentLayout(),profile.format||'image',currentNative());
});
$('#close-layout-editor').addEventListener('click',()=>$('#layout-editor').close());
$('#layout-format').add(new Option('电脑字体+打印机驱动（实验）','driver'));updateProfileSelectors();fillLayoutControls(savedLayout());fillNativeControls(activeProfile().nativeSettings);$('#layout-format').value=activeProfile().format||'image';applyEditorFormat($('#layout-format').value);$('#reprint-profile').value=layoutProfiles.activeId;$('#reprint-profile-wrap').hidden=true;persistProfiles();load();loadDiscarded();
$('#refresh-device-status').addEventListener('click',loadDeviceStatus);loadDeviceStatus();setInterval(()=>{if(!document.hidden)loadDeviceStatus();},15000);
