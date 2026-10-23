'use strict';
const $ = (id) => document.getElementById(id);
const names = {overview:'Tổng quan',devices:'Thiết bị',dictionary:'Từ điển cá nhân',subtitles:'Dịch trước phụ đề',video:'AI Video Translator',memory:'Kho dịch thuật',settings:'Cài đặt AI'};
const profileNames = {all:'Tất cả',game:'Game',manga:'Manga',document:'Tài liệu',web:'Website',technical:'Kỹ thuật'};
const cliUnsupported = 'Google không hỗ trợ cách tích hợp CLI này; tài khoản có thể bị hạn chế/đình chỉ.';
let token = location.hash.slice(1);
if (token) { sessionStorage.setItem('gemini-admin',token); history.replaceState(null,'',location.pathname); }
else token = sessionStorage.getItem('gemini-admin') || '';
let state = null, glossary = [], page = 'overview', pair = null, qrUrl = null, toastTimer, settingsFilled = false, polling = false, editingId = null;
const cliBusy = new Set();
let subtitleFile=null,subtitlePreview=null,subtitleBusy=false,subtitleRevision=0,subtitleReadRevision=0,subtitleSettingsSignature=null,subtitleJobs=[],subtitleJobsPolling=false;
function toast(message,error=false){const el=$('toast');el.textContent=message;el.classList.remove('hidden');el.classList.toggle('error',error);clearTimeout(toastTimer);toastTimer=setTimeout(()=>el.classList.add('hidden'),5500);}
async function api(path,options={}){
  const response=await fetch(path,{...options,headers:{Authorization:'Bearer '+token,...(options.body?{'Content-Type':'application/json'}:{}),...options.headers}});
  if(response.status===401){$('auth-error').classList.remove('hidden');throw Error('Hãy mở lại chương trình Gemini OCR trên Windows.');}
  if(!response.ok){let data;try{data=await response.json();}catch{}throw Error(typeof data?.detail==='string'?data.detail:'Không thể thực hiện thao tác. Kiểm tra dữ liệu và thử lại.');}
  return response.json();
}
function navigate(name){if(!names[name])return;page=name;document.querySelectorAll('.page').forEach(el=>el.classList.toggle('hidden',el.id!=='page-'+name));document.querySelectorAll('.nav').forEach(el=>el.classList.toggle('active',el.dataset.page===name));$('breadcrumb').textContent=names[name];if(name==='dictionary')loadGlossary().catch(e=>toast(e.message,true));if(name==='subtitles')loadSubtitleJobs().catch(showSubtitleJobsError);if(name==='video')loadVideoPage().catch(e=>showVideoError(e));if(name==='memory')loadMemory().catch(e=>toast(e.message,true));window.scrollTo(0,0);}
document.querySelectorAll('[data-page]').forEach(el=>el.addEventListener('click',()=>navigate(el.dataset.page)));
document.querySelectorAll('[data-goto]').forEach(el=>el.addEventListener('click',()=>navigate(el.dataset.goto)));
document.querySelector('.brand').addEventListener('click',event=>{event.preventDefault();navigate('overview');});
function text(tag,value,cls){const el=document.createElement(tag);el.textContent=value;if(cls)el.className=cls;return el;}
function metric(id,value,unit){const el=$(id);el.replaceChildren(document.createTextNode(String(value)),text('small',unit));}
function updateCliControls(){const enabled=$('personal-cli-enabled').checked,saved=state?.settings.personal_cli_enabled===true,allowed=enabled&&saved&&$('provider').value==='cli';for(const action of ['install','login','check','turbo'])$('cli-'+action).disabled=!allowed||cliBusy.has(action);$('cli-save-hint').textContent=enabled!==saved?'Nhấn Lưu cài đặt để áp dụng thay đổi CLI.':enabled?'CLI thử nghiệm đã bật. Kiểm tra tài khoản sẽ gửi một câu thử tới Gemini.':'CLI đang tắt. OCR chỉ dùng cache/từ điển khi chọn bộ máy CLI.';}
function providerFields(){const cli=$('provider').value==='cli';$('cli-settings').classList.toggle('hidden',!cli);$('api-settings').classList.toggle('hidden',cli);$('cli-model').required=cli;for(const id of ['fast-model','smart-model','quality-model'])$(id).required=!cli;updateCliControls();}
$('provider').addEventListener('change',providerFields);
$('personal-cli-enabled').addEventListener('change',updateCliControls);
function fillSettings(s){$('provider').value=s.provider||'api';$('personal-cli-enabled').checked=s.personal_cli_enabled===true;$('cli-model').value=s.cli_model||'auto';$('fast-model').value=s.fast_model;$('smart-model').value=s.smart_model;$('quality-model').value=s.quality_model;$('rpm').value=s.requests_per_minute;$('daily').value=s.daily_request_limit;$('cache-enabled').checked=s.cache_enabled;$('context-enabled').checked=s.context_enabled;providerFields();settingsFilled=true;}
async function refresh(){
 if(polling)return;polling=true;
 try{
  state=await api('/api/status');$('connection-error').classList.add('hidden');$('server-dot').classList.remove('offline');$('server-status').textContent='Máy chủ đang hoạt động';
  const connected=state.devices.filter(d=>d.connected).length;
  $('device-badge').textContent=state.devices.length;metric('metric-devices',connected,'thiết bị');$('device-hint').textContent=connected?'Sẵn sàng nhận văn bản từ Android':'Đang chờ điện thoại của bạn';
  metric('metric-latency',state.metrics.frames?state.metrics.latency_ms:'—','ms');
  const ratio=state.metrics.translated_blocks?Math.round(state.metrics.cache_hits/state.metrics.translated_blocks*100):'—';metric('metric-cache',ratio,'%');$('cache-hint').textContent=state.cache_entries.toLocaleString('vi-VN')+' bản dịch trong bộ nhớ đệm';
  metric('metric-calls',state.usage.requests,'lượt');$('calls-hint').textContent='Hạn mức '+state.settings.daily_request_limit.toLocaleString('vi-VN')+'/ngày UTC · '+(state.usage.input_tokens+state.usage.output_tokens).toLocaleString('vi-VN')+' token';$('version-label').textContent='v'+state.version;
  const usingCli=state.settings.provider==='cli';
  const cliEnabled=state.cli.experimental_enabled===true;
  $('setup-banner').classList.toggle('hidden',state.provider_configured&&(!usingCli||state.cli.checked));$('key-badge').textContent=usingCli?(cliEnabled?'CLI THỬ NGHIỆM':'CLI ĐANG TẮT'):(state.api_configured?'ĐÃ LƯU KHÓA':'CHƯA CẤU HÌNH');$('api-key').placeholder=state.api_configured?'Đã lưu khóa · để trống để giữ nguyên':'Dán khóa Gemini API tại đây';$('cli-status').textContent=cliEnabled?(state.cli.installing?'Đang tải công cụ CLI…':state.cli.login_active?'Cửa sổ đăng nhập đang mở. Hoàn tất đăng nhập, thoát /quit rồi kiểm tra tài khoản.':state.cli.message||'CLI thử nghiệm đã bật.')+' · '+cliUnsupported:'CLI thử nghiệm đang tắt. Cấu hình và đăng nhập hiện có được giữ nguyên.';$('provider-summary').textContent=usingCli?(cliEnabled?'Đang chọn CLI cá nhân thử nghiệm; Google không hỗ trợ tích hợp này.':'CLI đang tắt; bật lựa chọn thử nghiệm và lưu để dịch câu mới.'):'Đang chọn Gemini API chính thức bằng khóa riêng.';
  $('cli-speed').textContent=cliEnabled?'Phiên CLI giữ ấm: '+(state.cli.warm_sessions||0)+' · Mô hình gần nhất: '+(state.cli.model||'chưa gọi'):'Cache cũ vẫn dùng được khi câu nguồn trùng khớp.';
  $('events').replaceChildren(...state.events.slice(0,10).map(e=>{const row=text('div','', 'event '+e.level);const info=document.createElement('div');info.append(text('div',e.message),text('time',new Date(e.time*1000).toLocaleTimeString('vi-VN')));row.append(text('span',e.level==='error'?'!':'✓','event-icon'),info);return row;}));
  if(!settingsFilled)fillSettings(state.settings);else updateCliControls();
  const select=$('pair-host'),previous=select.value;
  if([...select.options].map(o=>o.value).join(',')!==state.addresses.join(',')){select.replaceChildren(...state.addresses.map(a=>{const opt=text('option',a+':'+state.port);opt.value=a;return opt;}));if(state.addresses.includes(previous))select.value=previous;}
  $('pair-address').textContent=select.value+':'+state.port;$('fingerprint').textContent=state.fingerprint;
  renderDevices();
  const subtitleSignature=JSON.stringify([state.settings,state.api_configured]);
  if(subtitleSettingsSignature!==null&&subtitleSettingsSignature!==subtitleSignature)invalidateSubtitlePreview();
  subtitleSettingsSignature=subtitleSignature;updateSubtitleControls();
  if(page==='subtitles'&&!document.hidden)await loadSubtitleJobs().catch(showSubtitleJobsError);
  if(page==='video'&&!document.hidden)await loadVideoPage().catch(showVideoError);
 }catch(error){$('server-dot').classList.add('offline');$('server-status').textContent='Chưa kết nối';if(token)$('connection-error').classList.remove('hidden');}
 finally{polling=false;}
}
function renderDevices(){
 if(!state.devices.length){const empty=text('div','','empty');empty.append(text('div','▣','empty-symbol'),text('b','Chưa có thiết bị nào'),text('p','Ghép cặp điện thoại đầu tiên để bắt đầu.'));$('devices-list').replaceChildren(empty);return;}
 $('devices-list').replaceChildren(...state.devices.map(d=>{const row=text('div','','device-card'),info=text('div','','device-info');info.append(text('b',d.name),text('small',d.connected?'● Đang kết nối':'Lần cuối: '+new Date(d.seen*1000).toLocaleString('vi-VN')));const remove=text('button','Hủy ghép cặp','button text-danger');remove.addEventListener('click',async()=>{if(!confirm('Hủy quyền kết nối của '+d.name+'?'))return;try{await api('/api/devices/'+d.id,{method:'DELETE'});await refresh();toast('Đã hủy ghép cặp.');}catch(e){toast(e.message,true);}});row.append(text('span','▣','device-symbol'),info,remove);return row;}));
}
function subtitleOptions(){return{source:$('subtitle-source').value,profile:$('subtitle-profile').value,mode:$('subtitle-mode').value};}
function invalidateSubtitlePreview(){subtitleRevision++;subtitlePreview=null;$('subtitle-preview-result').replaceChildren();$('subtitle-preview-result').classList.add('hidden');updateSubtitleControls();}
function subtitleStartBlock(){
 if(!subtitleFile||!subtitlePreview)return 'Chọn tệp và nhấn Xem trước trước khi bắt đầu.';
 if(!state)return 'Đang chờ kết nối Windows.';
 if(state.settings.provider!=='api')return 'Chọn Gemini API trong Cài đặt AI để dịch trước. Tính năng này không dùng CLI.';
 if(!state.api_configured)return 'Nhập khóa Gemini API trong Cài đặt AI trước khi bắt đầu.';
 if(!state.settings.cache_enabled)return 'Bật Cache thông minh trong Cài đặt AI để dùng kết quả trên Android.';
 if(subtitleJobs.some(job=>['queued','running'].includes(job.status)))return 'Đang có tác vụ dịch trước. Chờ hoàn tất hoặc nhấn Dừng bên dưới.';
 return '';
}
function updateSubtitleControls(){
 const block=subtitleStartBlock();
 $('subtitle-preview').disabled=subtitleBusy||!subtitleFile;
 $('subtitle-clear').disabled=subtitleBusy||!subtitleFile;
 $('subtitle-start').disabled=subtitleBusy||!!block;
 for(const id of ['subtitle-file','subtitle-source','subtitle-profile','subtitle-mode'])$(id).disabled=subtitleBusy;
 $('subtitle-start-hint').textContent=block||'Đã xem trước. Nhấn Bắt đầu sẽ gửi các câu chưa có cache tới Gemini API và sử dụng hạn mức API.';
}
function subtitleNumber(value){return Math.max(0,Number.isFinite(Number(value))?Number(value):0);}
function renderSubtitlePreview(data){
 const result=$('subtitle-preview-result');
 result.replaceChildren(text('b','Đã phân tích '+String(data.format||'').toUpperCase()+' · Chưa gọi Gemini'),
  text('p',subtitleNumber(data.cue_count).toLocaleString('vi-VN')+' đoạn phụ đề · '+subtitleNumber(data.unique_text_count).toLocaleString('vi-VN')+' câu khác nhau · '+subtitleNumber(data.duplicate_cue_count).toLocaleString('vi-VN')+' đoạn lặp.'),
  text('p','Dự kiến '+subtitleNumber(data.expected_batches).toLocaleString('vi-VN')+' lô; khoảng '+subtitleNumber(data.estimated_input_tokens).toLocaleString('vi-VN')+' token văn bản đầu vào. Chưa gồm prompt và đầu ra; không phải ước tính chi phí.'));
 if(Array.isArray(data.warnings))for(const warning of data.warnings)result.append(text('p',String(warning),'muted small'));
 result.classList.remove('hidden');
}
async function readSubtitleFile(file){
 if(file.size>2*1024*1024)throw Error('Tệp phụ đề không được lớn hơn 2 MiB.');
 if(!/\.(srt|vtt|ass|ssa)$/i.test(file.name))throw Error('Chọn tệp SRT, VTT, ASS hoặc SSA.');
 const bytes=new Uint8Array(await file.arrayBuffer());
 const encoding=bytes[0]===0xff&&bytes[1]===0xfe?'utf-16le':bytes[0]===0xfe&&bytes[1]===0xff?'utf-16be':'utf-8';
 let content;try{content=new TextDecoder(encoding,{fatal:true}).decode(bytes);}catch{throw Error('Không đọc được mã hóa. Hãy lưu tệp dưới dạng UTF-8 hoặc UTF-16 có BOM.');}
 if(!content.trim()||content.includes('\u0000'))throw Error('Tệp rỗng hoặc mã hóa không hợp lệ. Hãy dùng UTF-8 hoặc UTF-16 có BOM.');
 return{filename:file.name,content,encoding};
}
$('subtitle-file').addEventListener('change',async event=>{
 const file=event.target.files[0];subtitleFile=null;invalidateSubtitlePreview();const revision=++subtitleReadRevision;
 if(!file){$('subtitle-file-status').textContent='Chưa chọn tệp phụ đề.';return;}
 subtitleBusy=true;updateSubtitleControls();$('subtitle-file-status').textContent='Đang đọc tệp trên máy…';
 try{const loaded=await readSubtitleFile(file);if(revision!==subtitleReadRevision)return;subtitleFile={filename:loaded.filename,content:loaded.content};$('subtitle-file-status').textContent=file.name+' · '+(file.size/1024).toLocaleString('vi-VN',{maximumFractionDigits:1})+' KiB · '+loaded.encoding.toUpperCase()+'. Nhấn Xem trước để phân tích; chưa gửi tới Gemini.';}
 catch(error){event.target.value='';$('subtitle-file-status').textContent=error.message;toast(error.message,true);}
 finally{subtitleBusy=false;updateSubtitleControls();}
});
for(const id of ['subtitle-source','subtitle-profile','subtitle-mode'])$(id).addEventListener('change',invalidateSubtitlePreview);
$('subtitle-clear').addEventListener('click',()=>{subtitleReadRevision++;subtitleFile=null;$('subtitle-file').value='';$('subtitle-file-status').textContent='Đã bỏ tệp khỏi trang. Cache đã dịch trước vẫn được giữ.';invalidateSubtitlePreview();});
$('subtitle-preview').addEventListener('click',async()=>{
 if(!subtitleFile||subtitleBusy)return;
 invalidateSubtitlePreview();const revision=subtitleRevision;subtitleBusy=true;updateSubtitleControls();$('subtitle-preview').textContent='Đang phân tích…';
 try{const data=await api('/api/subtitles/preview',{method:'POST',body:JSON.stringify(subtitleFile)});if(revision!==subtitleRevision)return;subtitlePreview=data;renderSubtitlePreview(data);}
 catch(error){toast(error.message,true);}
 finally{subtitleBusy=false;$('subtitle-preview').textContent='Xem trước tệp';updateSubtitleControls();}
});
$('subtitle-start').addEventListener('click',async()=>{
 if(subtitleBusy||subtitleStartBlock())return;
 subtitleBusy=true;updateSubtitleControls();$('subtitle-start').textContent='Đang tạo tác vụ…';
 try{const job=await api('/api/subtitles/jobs',{method:'POST',body:JSON.stringify({...subtitleFile,...subtitleOptions()})});subtitleJobs=[job,...subtitleJobs.filter(item=>item.job_id!==job.job_id)];invalidateSubtitlePreview();renderSubtitleJobs();toast('Đã bắt đầu dịch trước bằng Gemini API.');await loadSubtitleJobs();}
 catch(error){toast(error.message,true);}
 finally{subtitleBusy=false;$('subtitle-start').textContent='Bắt đầu dịch bằng Gemini API';updateSubtitleControls();}
});
function showSubtitleJobsError(error){$('subtitle-jobs-error').textContent=error.message;$('subtitle-jobs-error').classList.remove('hidden');}
async function loadSubtitleJobs(){
 if(subtitleJobsPolling)return;
 subtitleJobsPolling=true;
 try{const jobs=await api('/api/subtitles/jobs');if(!Array.isArray(jobs))throw Error('Máy chủ trả danh sách tác vụ không hợp lệ.');subtitleJobs=jobs;$('subtitle-jobs-error').classList.add('hidden');renderSubtitleJobs();updateSubtitleControls();}
 finally{subtitleJobsPolling=false;}
}
function renderSubtitleJobs(){
 if(!subtitleJobs.length){$('subtitle-jobs').replaceChildren(text('p','Chưa có tác vụ dịch trước trong phiên máy chủ này.','muted'));return;}
 const statuses={queued:'Đang chờ',running:'Đang dịch',completed:'Hoàn tất',failed:'Đã dừng do lỗi',cancelled:'Đã hủy'};
 const languageNames={zh:'Trung',ja:'Nhật',en:'Anh',ko:'Hàn'};
 $('subtitle-jobs').replaceChildren(...subtitleJobs.map(job=>{
  const item=text('article','','panel'),heading=text('div','','panel-heading'),info=document.createElement('div');
  info.append(text('h3',statuses[job.status]||String(job.status)),text('p',(languageNames[job.source]||job.source)+' → Việt · '+(profileNames[job.profile]||job.profile)+' · '+job.mode+' · '+job.model));heading.append(info);
  if(['queued','running'].includes(job.status)){
   const stop=text('button','Dừng tác vụ','button secondary');stop.type='button';stop.addEventListener('click',async()=>{stop.disabled=true;try{await api('/api/subtitles/jobs/'+encodeURIComponent(job.job_id),{method:'DELETE'});await loadSubtitleJobs();toast('Đã dừng dịch trước. Các câu đã vào cache vẫn được giữ.');}catch(error){stop.disabled=false;showSubtitleJobsError(error);}});heading.append(stop);
  }
  const completed=subtitleNumber(job.completed_items),total=subtitleNumber(job.unique_texts),progress=document.createElement('progress');progress.max=Math.max(1,total);progress.value=Math.min(completed,total);progress.style.width='100%';progress.setAttribute('aria-label','Số câu khác nhau đã hoàn thành');
  item.append(heading,progress,text('p',completed.toLocaleString('vi-VN')+' / '+total.toLocaleString('vi-VN')+' câu khác nhau · '+subtitleNumber(job.completed_cues).toLocaleString('vi-VN')+' / '+subtitleNumber(job.total_cues).toLocaleString('vi-VN')+' đoạn phụ đề.'),
   text('p','Lô hoàn tất: '+subtitleNumber(job.completed_batches)+' / '+subtitleNumber(job.total_batches)+' · Đã thử: '+subtitleNumber(job.attempted_batches)+' · Câu từ cache: '+subtitleNumber(job.cached_items)+' · Câu dịch mới: '+subtitleNumber(job.translated_items),'muted small'),
   text('p','Lô gọi Gemini mới: '+subtitleNumber(job.new_translation_batches)+' · Đã chạy '+(subtitleNumber(job.elapsed_ms)/1000).toLocaleString('vi-VN',{maximumFractionDigits:1})+' giây.','muted small'));
  if(job.error_message)item.append(text('p',String(job.error_message)+(job.error_code?' ('+job.error_code+')':''),'muted small'));
  if(subtitleNumber(job.retry_after_ms)>0)item.append(text('p','Máy chủ yêu cầu chờ khoảng '+Math.ceil(subtitleNumber(job.retry_after_ms)/1000)+' giây tại thời điểm dừng. Tác vụ không tự gửi lại.','muted small'));
  return item;
 }));
}
$('subtitle-refresh').addEventListener('click',()=>loadSubtitleJobs().catch(showSubtitleJobsError));
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&page==='subtitles')loadSubtitleJobs().catch(showSubtitleJobsError);});
$('settings-form').addEventListener('input',invalidateSubtitlePreview);
$('refresh').addEventListener('click',()=>refresh());
$('test-form').addEventListener('submit',async event=>{
 event.preventDefault();const input=$('test-input').value.trim();if(!input)return;
 $('test-submit').disabled=true;$('test-submit').textContent='Đang dịch…';$('test-result').classList.add('hidden');
 try{const data=await api('/api/translate',{method:'POST',body:JSON.stringify({type:'translate',request_id:crypto.randomUUID(),source:$('test-language').value,profile:'game',mode:$('test-mode').value,blocks:[{id:'test',text:input,box:{x:0,y:0,width:1,height:1}}]})});$('test-result').replaceChildren(text('div',data.blocks[0].text),text('div',data.latency_ms+' ms · '+(data.blocks[0].cached?'Từ cache':state.settings.provider==='cli'?'Tài khoản Google · CLI':'Gemini API'),'small muted'));$('test-result').classList.remove('hidden');await refresh();}
 catch(e){toast(e.message,true);}finally{$('test-submit').disabled=false;$('test-submit').textContent='Dịch thử ↗';}
});
function pairingUri(){return 'gemocr://pair?'+new URLSearchParams({host:$('pair-host').value+':'+state.port,pin:state.fingerprint,code:pair.code});}
async function updateQR(){
 if(!pair||!state)return;
 const response=await fetch('/api/pairing/qr?'+new URLSearchParams({host:$('pair-host').value}),{headers:{Authorization:'Bearer '+token}});
 if(!response.ok)throw Error('Mã ghép cặp đã hết hạn. Hãy tạo mã mới.');
 if(qrUrl)URL.revokeObjectURL(qrUrl);qrUrl=URL.createObjectURL(await response.blob());$('pair-qr').src=qrUrl;$('pair-address').textContent=$('pair-host').value+':'+state.port;
}
$('pair-button').addEventListener('click',async()=>{const button=$('pair-button');button.disabled=true;try{const data=await api('/api/pairing',{method:'POST'});pair={...data,expires:Date.now()+data.expires_in*1000};await updateQR();$('pair-code').textContent=data.code;$('pairing-content').classList.remove('hidden');button.textContent='Tạo mã mới ↻';tickPair();}catch(e){toast(e.message,true);}finally{button.disabled=false;}});
$('pair-host').addEventListener('change',()=>{if(pair)updateQR().catch(e=>toast(e.message,true));});
function tickPair(){if(!pair)return;const left=Math.max(0,Math.ceil((pair.expires-Date.now())/1000));$('pair-countdown').textContent=left?'Mã còn hiệu lực '+left+' giây':'Mã đã hết hạn. Nhấn Tạo mã mới.';$('copy-pair').disabled=!left;if(!left){$('pair-qr').removeAttribute('src');$('pair-code').textContent='HẾT HẠN';}}
setInterval(tickPair,1000);
$('copy-pair').addEventListener('click',async()=>{try{await navigator.clipboard.writeText(pairingUri());toast('Đã sao chép. Gửi liên kết sang điện thoại của bạn và dán trong app.');}catch{toast('Trình duyệt chưa cho phép sao chép.',true);}});
async function loadGlossary(){glossary=await api('/api/glossary');renderGlossary();}
function renderGlossary(){const query=$('dict-search').value.toLocaleLowerCase('vi');const entries=glossary.filter(g=>(g.source+' '+g.target).toLocaleLowerCase('vi').includes(query));$('glossary-count').textContent='('+glossary.length+')';if(!entries.length){const row=document.createElement('tr'),cell=text('td',glossary.length?'Không có mục phù hợp.':'Chưa có mục từ. Thêm tên nhân vật hoặc thuật ngữ đầu tiên.','empty');cell.colSpan=4;row.append(cell);$('dictionary-list').replaceChildren(row);return;}
 $('dictionary-list').replaceChildren(...entries.map(g=>{const row=document.createElement('tr'),profile=document.createElement('td'),actions=document.createElement('td');profile.append(text('span',profileNames[g.profile],'tag'));const edit=text('button','Sửa','button secondary'),del=text('button','Xóa','button text-danger');edit.addEventListener('click',()=>{editingId=g.id;$('dict-source').value=g.source;$('dict-target').value=g.target;$('dict-profile').value=g.profile;$('dict-save').textContent='Cập nhật ✓';$('dict-cancel').classList.remove('hidden');$('dict-target').focus();});del.addEventListener('click',async()=>{try{await api('/api/glossary/'+g.id,{method:'DELETE'});if(editingId===g.id)resetDictionaryForm();await loadGlossary();toast('Đã xóa mục từ.');}catch(e){toast(e.message,true);}});actions.append(edit,del);row.append(text('td',g.source),text('td',g.target),profile,actions);return row;}));
}
$('dict-search').addEventListener('input',renderGlossary);
function resetDictionaryForm(){editingId=null;$('dict-source').value='';$('dict-target').value='';$('dict-save').textContent='Lưu mục từ +';$('dict-cancel').classList.add('hidden');}
$('dict-cancel').addEventListener('click',resetDictionaryForm);
$('dictionary-form').addEventListener('submit',async event=>{event.preventDefault();$('dict-save').disabled=true;try{await api('/api/glossary'+(editingId===null?'':'/'+editingId),{method:editingId===null?'POST':'PUT',body:JSON.stringify({source:$('dict-source').value.trim(),target:$('dict-target').value.trim(),profile:$('dict-profile').value})});resetDictionaryForm();await loadGlossary();toast('Đã lưu. Điện thoại sẽ nhận bản dịch mới.');}catch(e){toast(e.message,true);}finally{$('dict-save').disabled=false;}});
$('import-dictionary').addEventListener('click',()=>$('dictionary-file').click());
$('dictionary-file').addEventListener('change',async event=>{const file=event.target.files[0];if(!file)return;$('import-dictionary').disabled=true;try{if(file.size>1024*1024)throw Error('Tệp từ điển phải nhỏ hơn 1 MB.');let data;try{data=JSON.parse(await file.text());}catch{throw Error('Tệp JSON không hợp lệ. Hãy dùng tệp đã xuất từ Gemini OCR.');}const entries=Array.isArray(data)?data:data.entries;if(!Array.isArray(entries)||entries.length<1||entries.length>500)throw Error('Tệp cần từ 1 đến 500 mục {source, target, profile}.');await api('/api/glossary/import',{method:'POST',body:JSON.stringify({entries})});resetDictionaryForm();await loadGlossary();toast('Đã nhập '+entries.length+' mục từ.');}catch(e){toast(e.message,true);}finally{event.target.value='';$('import-dictionary').disabled=false;}});
$('export-dictionary').addEventListener('click',()=>{const blob=new Blob([JSON.stringify(glossary.map(({source,target,profile})=>({source,target,profile})),null,2)],{type:'application/json'});const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='GeminiOCR-tu-dien.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
function settingsPayload(){return{provider:$('provider').value,personal_cli_enabled:$('personal-cli-enabled').checked,cli_model:$('cli-model').value.trim(),fast_model:$('fast-model').value.trim(),smart_model:$('smart-model').value.trim(),quality_model:$('quality-model').value.trim(),requests_per_minute:Number($('rpm').value),daily_request_limit:Number($('daily').value),cache_enabled:$('cache-enabled').checked,context_enabled:$('context-enabled').checked,memory_enabled:state?.settings.memory_enabled===true};}
function requirePersonalCli(){if(!$('personal-cli-enabled').checked||state?.settings.personal_cli_enabled!==true)throw Error('Bật CLI cá nhân thử nghiệm và nhấn Lưu cài đặt trước.');}
for(const action of ['install','login','check'])$('cli-'+action).addEventListener('click',async()=>{try{requirePersonalCli();cliBusy.add(action);updateCliControls();const data=await api('/api/cli/'+action,{method:'POST'});$('cli-result').textContent=action==='check'?'Dịch thử thành công: '+data.translation:action==='login'?'Đã mở CLI. Hoàn tất đăng nhập Google, gõ /quit rồi kiểm tra tài khoản.':'Đang kiểm tra/tải công cụ CLI. Trạng thái sẽ tự cập nhật.';$('cli-result').classList.remove('hidden');await refresh();}catch(e){toast(e.message,true);}finally{cliBusy.delete(action);updateCliControls();}});
$('cli-turbo').addEventListener('click',async()=>{try{requirePersonalCli();cliBusy.add('turbo');updateCliControls();const body={...settingsPayload(),cli_model:'gemini-3.8-flash-low',cache_enabled:true,context_enabled:true,requests_per_minute:Math.max(60,Number($('rpm').value))};await api('/api/settings',{method:'PUT',body:JSON.stringify(body)});await refresh();fillSettings(state.settings);toast('Đã lưu chế độ tăng tốc: Flash Low, cache và phiên hội thoại giữ ấm.');}catch(e){toast(e.message,true);}finally{cliBusy.delete('turbo');updateCliControls();}});
$('settings-form').addEventListener('submit',async event=>{event.preventDefault();$('save-settings').disabled=true;try{const body=settingsPayload(),key=$('api-key').value.trim();if(key)body.api_key=key;await api('/api/settings',{method:'PUT',body:JSON.stringify(body)});$('api-key').value='';await refresh();toast('Đã lưu cấu hình AI.');}catch(e){toast(e.message,true);}finally{$('save-settings').disabled=false;}});
$('clear-cache').addEventListener('click',async()=>{if(!confirm('Xóa toàn bộ bản dịch cache trên máy tính?'))return;try{await api('/api/cache',{method:'DELETE'});await refresh();toast('Đã xóa cache và ngữ cảnh.');}catch(e){toast(e.message,true);}});
$('remove-key').addEventListener('click',async()=>{if(!state||!confirm('Xóa khóa Gemini API đã lưu trên Windows?'))return;try{await api('/api/settings',{method:'PUT',body:JSON.stringify({...state.settings,api_key:''})});await refresh();toast('Đã xóa khóa API.');}catch(e){toast(e.message,true);}});
$('shutdown').addEventListener('click',async()=>{if(!confirm('Tắt máy chủ và ngắt các phiên dịch?'))return;try{await api('/api/shutdown',{method:'POST'});toast('Đã tắt máy chủ. Mở lại Gemini OCR khi cần dịch.');}catch(e){toast(e.message,true);}});
if(!token)$('auth-error').classList.remove('hidden');
refresh();setInterval(refresh,3000);

// Video processing is explicitly started; selecting or polling a file never calls AI.
let videoCapabilities=null,videoJobs=[],videoJob=null,videoSelectedId=null,videoUploadBusy=false,videoActionBusy=false,videoPolling=false,videoDirty=false,videoTimelinePage=0,videoSelectionRevision=0,memoryBusy=false,memoryRows=[];
const VIDEO_PAGE_SIZE=40;
const videoStatusNames={imported:'Đã nhập · chưa gọi AI',queued:'Đang chờ',running:'Đang xử lý',analyzing:'Đang phân tích',extracting:'Đang nhận dạng',translating:'Đang dịch',rendering:'Đang render',exporting:'Đang xuất',paused:'Đã tạm dừng',paused_live:'Đợi Live OCR tạm dừng',waiting_live:'Đợi Live OCR tạm dừng',completed:'Hoàn tất',ready:'Sẵn sàng chỉnh sửa',cancelled:'Đã hủy',failed:'Có lỗi',interrupted:'Bị gián đoạn · có checkpoint'};
const videoStageNames={probe:'Phân tích video',analysis:'Phân tích video',subtitle:'Trích xuất phụ đề',extract:'Trích xuất nội dung',ocr:'Nhận dạng chữ',speech:'Nhận dạng lời nói',asr:'Nhận dạng lời nói',translation:'Dịch bằng Gemini',translate:'Dịch bằng Gemini',render:'Render video',export:'Xuất phụ đề',waiting_live:'Đợi Live OCR tạm dừng',complete:'Hoàn tất',completed:'Hoàn tất'};
function safeLocalUrl(value){if(typeof value!=='string'||!value)return null;try{const url=new URL(value,location.href);return url.origin===location.origin&&['http:','https:'].includes(url.protocol)?url.href:null;}catch{return null;}}
function videoId(job){return String(job?.id||'');}
function finiteNumber(value,fallback=0){const n=Number(value);return Number.isFinite(n)?n:fallback;}
function videoBytes(value){const size=finiteNumber(value);return size>=1024**3?(size/1024**3).toLocaleString('vi-VN',{maximumFractionDigits:2})+' GiB':(size/1024**2).toLocaleString('vi-VN',{maximumFractionDigits:1})+' MiB';}
function videoTime(ms){const seconds=Math.max(0,Math.round(finiteNumber(ms)/1000));return[Math.floor(seconds/3600),Math.floor(seconds/60)%60,seconds%60].map(n=>String(n).padStart(2,'0')).join(':');}
function videoEndpoint(id,action=''){return'/api/video/jobs/'+encodeURIComponent(id)+(action?'/'+action:'');}
function videoDetailEndpoint(id,offset=videoTimelinePage*VIDEO_PAGE_SIZE){return videoEndpoint(id)+'?offset='+offset+'&limit='+VIDEO_PAGE_SIZE;}
function showVideoError(error){$('video-jobs-error').textContent=error.message;$('video-jobs-error').classList.remove('hidden');}
function featureAvailable(feature){return feature===true||feature?.available===true;}
function videoModel(){const mode=$('video-mode').value;return state?.settings?.[mode+'_model']||'Chưa cấu hình';}
function videoBlockReason(){
 if(videoUploadBusy||videoActionBusy)return 'Đang thực hiện thao tác…';
 if(!videoJob)return 'Nhập hoặc chọn video trước khi bắt đầu.';
 if(!state||!videoCapabilities)return 'Đang chờ kết nối Windows.';
 if(state.settings.provider!=='api'||!state.api_configured)return 'Chọn Gemini API chính thức và lưu khóa trong Cài đặt AI trước khi dịch video.';
 if(!featureAvailable(videoCapabilities.ffmpeg))return 'Thiếu FFmpeg/ffprobe. Xem hướng dẫn công cụ bên trên.';
 if(['running','queued','extracting','translating','rendering','exporting'].includes(videoJob.status))return 'Video đang có tác vụ. Tạm dừng hoặc đợi hoàn tất trước khi bắt đầu lại.';
 const method=$('video-method').value;
 if(method==='ocr'&&!featureAvailable(videoCapabilities.ocr))return 'Bộ OCR chưa sẵn sàng. Cài công cụ theo hướng dẫn bên trên rồi Làm mới.';
 if(method==='speech'&&!featureAvailable(videoCapabilities.speech))return 'Bộ nhận dạng lời nói hoặc mô hình ASR chưa sẵn sàng.';
 const selected=videoJob.metadata?.streams?.find(s=>String(s.index)===$('video-stream').value);
 if(method==='subtitle'&&selected&&selected.is_text===false)return 'Luồng đang chọn là phụ đề dạng ảnh. Chọn OCR hoặc luồng phụ đề văn bản.';
 return '';
}
function updateVideoControls(){
 $('video-model').textContent=videoModel();
 const block=videoBlockReason();$('video-start').disabled=!!block;$('video-start-hint').textContent=block||'Nhấn Bắt đầu mới gửi văn bản đến Gemini API. Tác vụ sẽ nhường tài nguyên khi Live OCR hoạt động.';
 $('video-file').disabled=videoUploadBusy;$('video-save-memory').disabled=$('video-private').checked||state?.settings.memory_enabled!==true;if($('video-save-memory').disabled)$('video-save-memory').checked=false;
 $('video-roi').classList.toggle('hidden',!['auto','ocr'].includes($('video-method').value));
 $('video-save-timeline').disabled=videoActionBusy||!videoDirty;
 for(const format of ['srt','ass','mp4'])$('video-export-'+format).disabled=videoActionBusy||videoDirty||!videoJob?.segments?.length||videoJob.segments.some(s=>!String(s.translation||'').trim());
}
function renderVideoCapabilities(){
 if(!videoCapabilities)return;
 const parts=[];for(const [key,label]of[['ffmpeg','FFmpeg / ffprobe'],['ocr','OCR'],['speech','Nhận dạng lời nói']]){const item=videoCapabilities[key];parts.push(label+': '+(featureAvailable(item)?'sẵn sàng':'chưa sẵn sàng')+(item?.message?' · '+item.message:''));}
 if(videoCapabilities.live_active)parts.push('Live OCR đang hoạt động: tác vụ video sẽ chờ để bảo vệ độ trễ live.');
 $('video-capabilities').textContent=parts.join(' | ');
}
async function loadVideoPage(){
 if(videoPolling||videoUploadBusy)return;videoPolling=true;
 try{const [caps,jobs]=await Promise.all([api('/api/video/capabilities'),api('/api/video/jobs')]);videoCapabilities=caps;if(!Array.isArray(jobs))throw Error('Danh sách tác vụ video không hợp lệ.');videoJobs=jobs;renderVideoCapabilities();renderVideoJobs();$('video-jobs-error').classList.add('hidden');if(videoSelectedId&&!videoDirty){const wantedId=videoSelectedId,revision=videoSelectionRevision,detail=await api(videoDetailEndpoint(wantedId));if(wantedId===videoSelectedId&&revision===videoSelectionRevision&&!videoDirty)renderVideoDetail(detail,false);}updateVideoControls();}
 finally{videoPolling=false;}
}
function renderVideoJobs(){
 if(!videoJobs.length){$('video-jobs').replaceChildren(text('p','Chưa có video. Chọn tệp để bắt đầu; chưa gọi Gemini.','muted'));return;}
 $('video-jobs').replaceChildren(...videoJobs.map(job=>{
  const item=text('article','','video-job'+(videoId(job)===videoSelectedId?' selected':'')),top=text('div','','video-job-top'),info=document.createElement('div'),progress=job.progress||{};
  info.append(text('h3',job.name||'Video'),text('p',(videoStatusNames[job.status]||job.status||'Đang chờ')+' · '+(videoStageNames[progress.stage]||progress.stage||'Đã lưu video'),'muted small'));
  const select=text('button',videoId(job)===videoSelectedId?'Đang mở':'Mở / chỉnh sửa','button secondary');select.addEventListener('click',()=>selectVideoJob(videoId(job)).catch(e=>toast(e.message,true)));top.append(info,select);item.append(top);
  const percent=finiteNumber(progress.percent,-1);if(percent>=0){const bar=document.createElement('progress');bar.max=100;bar.value=Math.max(0,Math.min(100,percent));bar.setAttribute('aria-label','Tiến trình '+(job.name||'video'));item.append(bar);}
  const total=finiteNumber(progress.segments),translated=finiteNumber(progress.translated),hits=finiteNumber(progress.cache_hits);item.append(text('p',translated.toLocaleString('vi-VN')+'/'+total.toLocaleString('vi-VN')+' câu đã dịch · '+hits+' câu từ bộ nhớ'+(translated?' ('+Math.round(hits/translated*100)+'%)':'')+' · '+finiteNumber(progress.new_memory)+' mục kho mới · '+finiteNumber(progress.ai_calls)+' lượt Gemini','muted small'));
  if(progress.input_tokens!=null||progress.output_tokens!=null)item.append(text('p',finiteNumber(progress.input_tokens)+' token vào / '+finiteNumber(progress.output_tokens)+' token ra. Chưa có giá API đáng tin cậy để tính chi phí.','muted small'));
  if(job.error)item.append(text('p',typeof job.error==='string'?job.error:job.error.message||'Tác vụ lỗi. Có thể khôi phục từ checkpoint.','video-error'));
  const actions=text('div','','button-group video-toolbar'),running=['running','queued','extracting','translating','analyzing','rendering','exporting'].includes(job.status);
  if(running)actions.append(videoActionButton(job,'pause','Tạm dừng'));
  if(['paused','paused_live','waiting_live'].includes(job.status))actions.append(videoActionButton(job,'resume','Tiếp tục'));
  if(['failed','interrupted','cancelled'].includes(job.status))actions.append(videoActionButton(job,'recover','Khôi phục checkpoint'));
  if(running||['paused','paused_live','waiting_live'].includes(job.status))actions.append(videoActionButton(job,'cancel','Hủy tác vụ'));
  const remove=text('button','Xóa bản sao dự án','button text-danger');remove.disabled=videoActionBusy||running;remove.addEventListener('click',async()=>{if(!confirm('Xóa bản sao video, tệp xuất và dữ liệu kho thuộc dự án này? Video gốc trên máy không bị xóa.'))return;try{await api(videoEndpoint(videoId(job)),{method:'DELETE'});if(videoSelectedId===videoId(job)){videoSelectedId=null;videoJob=null;videoDirty=false;$('video-editor').classList.add('hidden');$('video-metadata').classList.add('hidden');$('video-preview').removeAttribute('src');$('video-preview').load();}await loadVideoPage();toast('Đã xóa bản sao dự án.');}catch(e){toast(e.message,true);}});actions.append(remove);item.append(actions);return item;
 }));
}
function videoActionButton(job,action,label){const button=text('button',label,'button secondary');button.disabled=videoActionBusy;button.addEventListener('click',async()=>{if(videoActionBusy)return;videoActionBusy=true;updateVideoControls();try{const data=await api(videoEndpoint(videoId(job),action),{method:'POST',body:'{}'});if(videoId(job)===videoSelectedId&&!videoDirty)renderVideoDetail(data,false);toast(action==='pause'?'Đã yêu cầu tạm dừng ở checkpoint gần nhất.':action==='cancel'?'Đã yêu cầu hủy tác vụ.':'Đã yêu cầu tiếp tục từ checkpoint.');}catch(e){toast(e.message,true);}finally{videoActionBusy=false;await loadVideoPage().catch(showVideoError);updateVideoControls();}});return button;}
function uploadVideoFile(file){
 if(!file||videoUploadBusy)return;
 if(videoDirty&&!confirm('Timeline chưa lưu. Tiếp tục nhập video khác và bỏ thay đổi đang sửa?'))return;
 const max=finiteNumber(videoCapabilities?.limits?.max_upload_bytes??videoCapabilities?.limits?.max_video_bytes,20*1024**3);if(file.size>max){toast('Video vượt giới hạn '+videoBytes(max)+'.',true);return;}
 videoUploadBusy=true;updateVideoControls();$('video-upload-progress').classList.remove('hidden');$('video-upload-progress').value=0;$('video-upload-status').textContent='Đang sao chép '+file.name+' vào máy chủ cục bộ…';
 const xhr=new XMLHttpRequest(),body=new FormData();body.append('file',file,file.name);xhr.open('POST','/api/video/import');xhr.setRequestHeader('Authorization','Bearer '+token);xhr.responseType='json';
 xhr.upload.addEventListener('progress',event=>{if(event.lengthComputable){$('video-upload-progress').value=event.loaded/event.total*100;$('video-upload-status').textContent='Đang sao chép '+file.name+' · '+Math.round(event.loaded/event.total*100)+'%';}});
 const finish=()=>{videoUploadBusy=false;$('video-file').value='';$('video-upload-progress').classList.add('hidden');updateVideoControls();};
 xhr.addEventListener('load',async()=>{try{const data=xhr.response;if(xhr.status===401)throw Error('Phiên quản trị hết hiệu lực. Mở lại Windows Companion.');if(xhr.status<200||xhr.status>=300)throw Error(typeof data?.detail==='string'?data.detail:'Không nhập được video. Kiểm tra định dạng, dung lượng và FFmpeg.');if(!videoId(data))throw Error('Máy chủ chưa trả mã dự án video.');videoSelectionRevision++;videoDirty=false;videoSelectedId=videoId(data);videoTimelinePage=0;renderVideoDetail(data,true);$('video-upload-status').textContent='Đã nhập '+file.name+' · '+videoBytes(file.size)+'. Chưa gọi Gemini.';finish();await loadVideoPage();}catch(e){finish();$('video-upload-status').textContent=e.message;toast(e.message,true);}});
 xhr.addEventListener('error',()=>{finish();$('video-upload-status').textContent='Mất kết nối khi nhập video. Video gốc được giữ nguyên.';toast('Không kết nối được máy chủ khi nhập video.',true);});xhr.addEventListener('abort',()=>{finish();$('video-upload-status').textContent='Đã dừng tải video.';});xhr.send(body);
}
async function selectVideoJob(id){if(videoDirty&&!confirm('Timeline chưa lưu. Bỏ thay đổi để mở lại video?'))return;const revision=++videoSelectionRevision,data=await api(videoDetailEndpoint(id,0));if(revision!==videoSelectionRevision)return;videoSelectedId=id;videoDirty=false;videoTimelinePage=0;renderVideoDetail(data,true);renderVideoJobs();}
async function changeVideoTimelinePage(direction){if(!videoJob||videoActionBusy)return;if(videoDirty){toast('Lưu timeline trước khi chuyển trang để giữ thay đổi.',true);return;}const total=finiteNumber(videoJob.total_segments,videoJob.segments?.length||0),next=Math.max(0,Math.min(videoTimelinePage+direction,Math.max(0,Math.ceil(total/VIDEO_PAGE_SIZE)-1))),revision=++videoSelectionRevision,id=videoSelectedId;videoActionBusy=true;updateVideoControls();try{const detail=await api(videoDetailEndpoint(id,next*VIDEO_PAGE_SIZE));if(id!==videoSelectedId||revision!==videoSelectionRevision)return;videoTimelinePage=next;renderVideoDetail(detail,false);}catch(e){toast(e.message,true);}finally{videoActionBusy=false;updateVideoControls();}}
function renderVideoDetail(job,resetOptions){
 if(!job||!videoId(job))return;const previousId=videoId(videoJob);videoJob=job;videoSelectedId=videoId(job);const metadata=job.metadata||{},box=$('video-metadata');box.replaceChildren(text('b',job.name||'Video'),text('p',videoBytes(job.size_bytes??metadata.size_bytes)+' · '+videoTime(metadata.duration_ms)+' · '+finiteNumber(metadata.width)+' × '+finiteNumber(metadata.height)+' · '+(metadata.fps||'—')+' fps'));if(Array.isArray(metadata.warnings))for(const warning of metadata.warnings)box.append(text('p',String(warning),'muted'));box.classList.remove('hidden');
 const streams=Array.isArray(metadata.streams)?metadata.streams.filter(s=>s.codec_type==='subtitle'):[],streamSelect=$('video-stream'),previous=streamSelect.value;streamSelect.replaceChildren(text('option','Tự chọn luồng phù hợp'));streamSelect.options[0].value='';for(const s of streams){const option=text('option','#'+s.index+' · '+(s.language||'không rõ ngôn ngữ')+' · '+s.codec_name+' · '+(s.is_text?'văn bản':'dạng ảnh')+(s.title?' · '+s.title:''));option.value=String(s.index);streamSelect.append(option);}if([...streamSelect.options].some(o=>o.value===previous))streamSelect.value=previous;
 $('video-stream-hint').textContent=streams.length?streams.length+' luồng phụ đề. Phụ đề dạng ảnh cần OCR; không thể trích xuất trực tiếp thành văn bản.':'Không phát hiện luồng phụ đề. Chọn OCR cho chữ ghi sẵn hoặc Speech nếu video có lời nói.';
 if(resetOptions){const options=job.options||{};for(const key of ['source','method','profile','mode'])if(options[key])$('video-'+key).value=options[key];if(options.stream_index!=null)streamSelect.value=String(options.stream_index);$('video-private').checked=options.private!==false;$('video-save-memory').checked=options.save_memory===true;$('video-use-memory').checked=options.use_memory!==false;if(Array.isArray(options.roi)&&options.roi.length===4)['left','top','right','bottom'].forEach((key,i)=>$('video-roi-'+key).value=String(options.roi[i]));}
 const preview=safeLocalUrl(job.preview_url);if(preview&&(previousId!==videoId(job)||!$('video-preview').getAttribute('src'))){$('video-preview').src=preview;}
 $('video-editor-title').textContent='3. Xem trước và chỉnh sửa · '+(job.name||'Video');$('video-editor').classList.toggle('hidden',!job.segments?.length);if(job.segments?.length){renderVideoTimeline();renderVideoDownloads();updateVideoPreview();}updateVideoControls();
}
function videoOptions(){const roi=['left','top','right','bottom'].map(key=>finiteNumber($('video-roi-'+key).value,-1));if(roi.some(v=>v<0||v>1)||roi[0]>=roi[2]||roi[1]>=roi[3])throw Error('Vùng OCR cần thỏa 0 ≤ trái < phải ≤ 1 và 0 ≤ trên < dưới ≤ 1.');return{source:$('video-source').value,method:$('video-method').value,stream_index:$('video-stream').value===''?null:Number($('video-stream').value),profile:$('video-profile').value,mode:$('video-mode').value,use_memory:$('video-use-memory').checked,private:$('video-private').checked,save_memory:!$('video-private').checked&&$('video-save-memory').checked,roi};}
function markVideoDirty(){videoDirty=true;$('video-edit-status').textContent='Có thay đổi chưa lưu. Lưu timeline trước khi xuất.';updateVideoControls();updateVideoPreview();}
function renderVideoTimeline(){
 const segments=videoJob?.segments||[],total=finiteNumber(videoJob?.total_segments,segments.length),pages=Math.max(1,Math.ceil(total/VIDEO_PAGE_SIZE));videoTimelinePage=Math.max(0,Math.min(videoTimelinePage,pages-1));
 $('video-timeline-count').textContent=total+' đoạn · Trang '+(videoTimelinePage+1)+'/'+pages;$('video-timeline-prev').disabled=videoTimelinePage===0;$('video-timeline-next').disabled=videoTimelinePage>=pages-1;
 $('video-timeline').replaceChildren(...segments.slice(0,VIDEO_PAGE_SIZE).map(segment=>{
  const row=document.createElement('tr'),times=document.createElement('td'),source=document.createElement('td'),target=document.createElement('td'),quality=document.createElement('td');
  for(const [field,label]of[['start_ms','Bắt đầu'],['end_ms','Kết thúc']]){const el=document.createElement('input');el.type='number';el.min='0';el.step='0.001';el.value=String(finiteNumber(segment[field])/1000);el.setAttribute('aria-label',label+' đoạn '+segment.id+' (giây)');el.addEventListener('input',()=>{segment[field]=Math.round(finiteNumber(el.value,-1)*1000);markVideoDirty();});times.append(el);}
  const seek=text('button','▶ Xem đoạn','button secondary');seek.addEventListener('click',()=>{$('video-preview').currentTime=Math.max(0,finiteNumber(segment.start_ms)/1000);updateVideoPreview();});times.append(seek);
  const original=document.createElement('textarea');original.rows=2;original.value=segment.text||'';original.setAttribute('aria-label','Nguyên văn đoạn '+segment.id);original.addEventListener('input',()=>{segment.text=original.value;segment.quality='draft';approve.textContent='Xác nhận';markVideoDirty();});source.append(original);if(segment.confidence!=null)source.append(text('small','Độ tin cậy nhận dạng: '+finiteNumber(segment.confidence).toLocaleString('vi-VN',{maximumFractionDigits:2}),'muted'));
  const translation=document.createElement('textarea');translation.rows=2;translation.value=segment.translation||'';translation.setAttribute('aria-label','Tiếng Việt đoạn '+segment.id);translation.addEventListener('input',()=>{segment.translation=translation.value;segment.quality='draft';approve.textContent='Xác nhận';markVideoDirty();});target.append(translation);
  const approve=text('button',segment.quality==='user_approved'?'Đã xác nhận ✓':'Xác nhận','button secondary');approve.addEventListener('click',()=>{if(!String(segment.translation||'').trim()){toast('Cần có bản dịch trước khi xác nhận.',true);return;}segment.quality='user_approved';approve.textContent='Đã xác nhận ✓';markVideoDirty();});quality.append(approve,text('small',segment.source_type||'', 'muted'));row.append(times,source,target,quality);return row;
 }));
}
function validateVideoTimeline(){const segments=videoJob?.segments||[];let previousEnd=0;for(const segment of segments){if(!Number.isFinite(segment.start_ms)||!Number.isFinite(segment.end_ms)||segment.start_ms<0||segment.end_ms<=segment.start_ms)throw Error('Thời gian không hợp lệ ở đoạn '+segment.id+'.');if(segment.start_ms<previousEnd)throw Error('Các đoạn phụ đề đang chồng thời gian. Sửa timeline trước khi lưu.');if(!String(segment.text||'').trim())throw Error('Nguyên văn trống ở đoạn '+segment.id+'.');previousEnd=segment.end_ms;}}
function videoStyle(){return{font_name:$('video-font').value.trim()||'Arial',font_size:finiteNumber($('video-font-size').value,40),color:$('video-color').value,outline:finiteNumber($('video-outline').value,2),shadow:finiteNumber($('video-shadow').value,1),position:$('video-position').value,background:$('video-background').checked};}
function updateVideoPreview(){
 const el=$('video-preview-subtitle'),time=$('video-preview').currentTime*1000,segment=videoJob?.segments?.find(s=>time>=s.start_ms&&time<s.end_ms);el.replaceChildren();if(!segment){el.classList.add('hidden');return;}const style=videoStyle();el.textContent=($('video-bilingual').checked?segment.text+'\n':'')+(segment.translation||'');el.style.fontFamily=style.font_name+', "Segoe UI", sans-serif';el.style.fontSize=Math.max(12,Math.min(36,style.font_size*.55))+'px';el.style.color=style.color;el.style.textShadow=style.outline?'1px 1px '+Math.max(1,style.outline)+'px #000, -1px -1px '+Math.max(1,style.outline)+'px #000':style.shadow?'1px 2px 3px #000':'none';el.classList.toggle('has-background',style.background);el.dataset.position=style.position;el.classList.remove('hidden');
}
function renderVideoDownloads(){const outputs=Array.isArray(videoJob?.outputs)?videoJob.outputs:[];$('video-downloads').replaceChildren(...outputs.map(output=>{const url=safeLocalUrl(output.url);if(!url)return text('p','Liên kết xuất chưa sẵn sàng.','muted');const link=text('a','Tải '+String(output.format||'tệp').toUpperCase()+' · '+(output.name||''),'button secondary');link.href=url;link.download=output.name||'';link.rel='noreferrer';return link;}));$('video-export-status').textContent=videoJob?.progress?.stage==='render'?'ĐANG RENDER':outputs.length?'TỆP XUẤT SẴN SÀNG':'CHƯA XUẤT';}
async function exportVideo(format){if(!videoJob||videoActionBusy)return;if(videoDirty){toast('Lưu timeline trước khi xuất.',true);return;}videoActionBusy=true;updateVideoControls();try{const quality=$('video-export-quality').value;const data=await api(videoEndpoint(videoSelectedId,'export'),{method:'POST',body:JSON.stringify({format,style:videoStyle(),bilingual:$('video-bilingual').checked,quality:['fast','balanced','quality'].includes(quality)?quality:'balanced'})});renderVideoDetail(data,false);toast(format==='mp4'?'Đã tạo tác vụ render. Theo dõi tiến trình và tải tệp khi hoàn tất.':'Đã yêu cầu xuất '+format.toUpperCase()+'.');await loadVideoPage();}catch(e){toast(e.message,true);}finally{videoActionBusy=false;updateVideoControls();}}
$('video-file').addEventListener('change',event=>uploadVideoFile(event.target.files[0]));
for(const type of ['dragenter','dragover'])$('video-drop').addEventListener(type,event=>{event.preventDefault();$('video-drop').classList.add('dragging');});
for(const type of ['dragleave','drop'])$('video-drop').addEventListener(type,event=>{event.preventDefault();$('video-drop').classList.remove('dragging');});
$('video-drop').addEventListener('drop',event=>uploadVideoFile(event.dataTransfer?.files?.[0]));
$('video-refresh').addEventListener('click',()=>loadVideoPage().catch(showVideoError));
for(const id of ['video-mode','video-method','video-stream'])$(id).addEventListener('change',updateVideoControls);
$('video-private').addEventListener('change',updateVideoControls);
$('video-start').addEventListener('click',async()=>{if(videoBlockReason())return;let options;try{options=videoOptions();}catch(e){toast(e.message,true);return;}if(videoDirty){toast('Lưu timeline trước khi bắt đầu lại.',true);return;}videoActionBusy=true;updateVideoControls();try{const data=await api(videoEndpoint(videoSelectedId,'start'),{method:'POST',body:JSON.stringify(options)});renderVideoDetail(data,false);toast('Đã bắt đầu tác vụ video bằng Gemini API.');await loadVideoPage();}catch(e){toast(e.message,true);}finally{videoActionBusy=false;updateVideoControls();}});
$('video-save-timeline').addEventListener('click',async()=>{if(!videoJob||videoActionBusy)return;try{validateVideoTimeline();videoActionBusy=true;updateVideoControls();await api(videoEndpoint(videoSelectedId,'timeline'),{method:'PUT',body:JSON.stringify({segments:videoJob.segments})});videoDirty=false;const data=await api(videoDetailEndpoint(videoSelectedId));renderVideoDetail(data,false);$('video-edit-status').textContent='Đã lưu timeline và các xác nhận của bạn.';toast('Đã lưu timeline.');}catch(e){toast(e.message,true);}finally{videoActionBusy=false;updateVideoControls();}});
$('video-timeline-prev').addEventListener('click',()=>changeVideoTimelinePage(-1));$('video-timeline-next').addEventListener('click',()=>changeVideoTimelinePage(1));
$('video-replace-all').addEventListener('click',async()=>{const find=$('video-find').value,replacement=$('video-replace').value;if(!find||!videoJob?.segments)return;if(videoDirty){toast('Lưu timeline trước khi tìm và thay toàn dự án.',true);return;}if(!confirm('Thay tất cả “'+find+'” bằng “'+replacement+'” trong bản dịch của video? Những câu bạn sửa sẽ được đánh dấu User Approved.'))return;try{const data=await api(videoEndpoint(videoSelectedId,'replace'),{method:'POST',body:JSON.stringify({search:find,replacement})});await loadVideoPage();toast('Đã thay trong '+finiteNumber(data.changed)+' đoạn của dự án.');}catch(e){toast(e.message,true);}});
$('video-approve-all').addEventListener('click',()=>{let count=0;for(const segment of videoJob?.segments?.slice(0,VIDEO_PAGE_SIZE)||[])if(String(segment.translation||'').trim()){segment.quality='user_approved';count++;}if(count){markVideoDirty();renderVideoTimeline();}toast('Đã xác nhận '+count+' đoạn trên trang. Lưu timeline để áp dụng.');});
$('video-preview').addEventListener('timeupdate',updateVideoPreview);
$('video-preview').addEventListener('error',()=>{$('video-preview-note').textContent='Trình duyệt chưa phát được codec video nguồn này. Bạn vẫn có thể sửa timeline và xuất MP4 H.264 để xem; tệp nguồn được giữ nguyên.';});
for(const id of ['video-font','video-font-size','video-color','video-outline','video-shadow','video-position','video-background','video-bilingual'])$(id).addEventListener('input',updateVideoPreview);
for(const format of ['srt','ass','mp4'])$('video-export-'+format).addEventListener('click',()=>exportVideo(format));

function memoryQuality(value){return{draft:'Draft · chưa xác minh',verified:'Verified · đã kiểm tra',user_approved:'User Approved'}[value]||value||'Draft';}
async function loadMemory(){if(memoryBusy)return;memoryBusy=true;try{const query=new URLSearchParams({limit:'100'});for(const [key,id]of[['search','memory-search'],['quality','memory-quality'],['source','memory-source'],['project_id','memory-project']])if($(id).value.trim())query.set(key,$(id).value.trim());const [data,settings]=await Promise.all([api('/api/memory?'+query),api('/api/memory/settings')]);memoryRows=Array.isArray(data)?data:data.entries||[];$('memory-enabled').checked=settings.enabled===true;$('memory-setting-status').textContent=settings.enabled?'Đang cho phép lưu dữ liệu mới trên máy; video riêng tư vẫn bị loại trừ.':'Tích lũy đang tắt. Không tự lưu nguyên văn mới vào Translation Memory.';$('memory-summary').textContent='Hiển thị '+memoryRows.length+' mục'+(data.total!=null?' / '+data.total:'')+'. Tối đa 100 mục mỗi lần tra cứu.';renderMemory();}finally{memoryBusy=false;}}
function renderMemory(){if(!memoryRows.length){const row=document.createElement('tr'),cell=text('td','Chưa có mục phù hợp. Bật tích lũy nếu bạn muốn lưu những bản dịch mới.','muted');cell.colSpan=4;row.append(cell);$('memory-list').replaceChildren(row);return;}$('memory-list').replaceChildren(...memoryRows.map(entry=>{const row=document.createElement('tr'),original=document.createElement('td'),target=document.createElement('td'),status=document.createElement('td'),actions=document.createElement('td');original.append(text('p',entry.original_text),text('small',(entry.source_language||'')+' → vi · '+(entry.profile||'')+' · '+(entry.context_scope||'')+(entry.project_id?' · Dự án '+entry.project_id:''),'muted'));const edit=document.createElement('textarea');edit.rows=2;edit.value=entry.translation||'';edit.maxLength=12000;edit.setAttribute('aria-label','Sửa tiếng Việt cho '+String(entry.original_text||'').slice(0,80));target.append(edit);status.append(text('span',memoryQuality(entry.quality),'tag'),text('p',(entry.source_type||'')+' · '+(entry.model||''),'muted small'),text('p',finiteNumber(entry.hit_count)+' lần dùng lại','muted small'));const save=text('button','Lưu & xác nhận','button secondary');save.addEventListener('click',async()=>{if(!edit.value.trim()){toast('Bản dịch không được trống.',true);return;}save.disabled=true;try{await api('/api/memory/'+encodeURIComponent(entry.content_hash),{method:'PATCH',body:JSON.stringify({translation:edit.value.trim(),quality:'user_approved'})});toast('Đã lưu bản dịch User Approved.');await loadMemory();}catch(e){toast(e.message,true);}finally{save.disabled=false;}});const remove=text('button','Xóa','button text-danger');remove.addEventListener('click',async()=>{if(!confirm('Xóa mục dịch thuật này khỏi kho trên máy?'))return;try{await api('/api/memory/'+encodeURIComponent(entry.content_hash),{method:'DELETE'});await loadMemory();toast('Đã xóa mục dịch thuật.');}catch(e){toast(e.message,true);}});actions.append(save,remove);row.append(original,target,status,actions);return row;}));}
$('memory-refresh').addEventListener('click',()=>loadMemory().catch(e=>toast(e.message,true)));$('memory-search-button').addEventListener('click',()=>loadMemory().catch(e=>toast(e.message,true)));$('memory-search').addEventListener('keydown',event=>{if(event.key==='Enter'){event.preventDefault();loadMemory().catch(e=>toast(e.message,true));}});
$('memory-enabled').addEventListener('change',async()=>{const enabled=$('memory-enabled').checked;$('memory-enabled').disabled=true;try{await api('/api/memory/settings',{method:'PUT',body:JSON.stringify({enabled})});$('memory-setting-status').textContent=enabled?'Đã bật tích lũy. Bản mới được lưu Draft; video riêng tư bị loại trừ.':'Đã tắt tích lũy dữ liệu mới. Mục cũ vẫn giữ cho đến khi bạn xóa.';await refresh();}catch(e){$('memory-enabled').checked=!enabled;toast(e.message,true);}finally{$('memory-enabled').disabled=false;}});
$('memory-delete-project').addEventListener('click',async()=>{const project=$('memory-project').value.trim();if(!project){toast('Nhập chính xác mã dự án cần xóa vào bộ lọc Dự án.',true);return;}if(!confirm('Xóa dữ liệu Translation Memory của dự án '+project+'? Tệp video và các dự án khác được giữ nguyên.'))return;try{await api('/api/memory/projects/'+encodeURIComponent(project),{method:'DELETE'});await loadMemory();toast('Đã xóa dữ liệu kho của dự án.');}catch(e){toast(e.message,true);}});

