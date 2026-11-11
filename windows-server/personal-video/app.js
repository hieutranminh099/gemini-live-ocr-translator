'use strict';
const $=id=>document.getElementById(id);
let token=new URLSearchParams(location.hash.slice(1)).get('access')||'';
let current=null,uploading=false,polling=false,loadingModels=false,modelLoadAttempted=false;
function notice(message){$('notice').textContent=message||'';$('notice').hidden=!message;}
async function api(path,options={}){
 const response=await fetch(path,{...options,credentials:'same-origin',headers:{...(token?{Authorization:'Bearer '+token}:{}),'Content-Type':'application/json',...options.headers}});
 let data;try{data=await response.json();}catch{throw Error('Chưa đọc được phản hồi từ app.');}
 if(!response.ok)throw Error(data.error||data.detail||'Thao tác chưa hoàn tất.');return data;
}
function element(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;}
const stages={imported:'Đã nhận phim',queued:'Đang chuẩn bị',extracting:'Đang lấy phụ đề',translating:'Đang dịch',exporting:'Đang tạo video',ready:'Đang chuẩn bị kết quả',completed:'Đã có kết quả',failed:'Cần tiếp tục',interrupted:'Đã lưu tiến độ',cancelled:'Đã tạm dừng',paused:'Đã tạm dừng',waiting_live:'Đang chờ tài nguyên'};
function renderJobs(jobs){
 $('jobs').replaceChildren();if(!jobs.length){$('jobs').append(element('p','Phim và kết quả sẽ xuất hiện ở đây.','empty'));return;}
 for(const job of jobs){
  const card=element('article',undefined,'job');card.append(element('h3',job.name));
  card.append(element('p',stages[job.status]||'Đang xử lý'));
  const p=job.progress||{};if(p.segments)card.append(element('p',`Đã dịch ${p.translated||0}/${p.segments} câu`));
  const progress=element('progress');progress.max=100;progress.value=Number(p.percent)||0;progress.setAttribute('aria-label','Tiến độ công đoạn');card.append(progress);
  if(job.error)card.append(element('p',job.error,'error'));
  const actions=element('div',undefined,'job-actions');
  for(const output of job.outputs||[]){if(!['mp4','srt'].includes(output.format))continue;const a=element('a',output.format==='mp4'?'Tải video':'Tải phụ đề SRT','button');a.href=output.url;a.download='';actions.append(a);}
  const active=current.busy&&current.active_id===job.id;
  if(active||['failed','interrupted','cancelled'].includes(job.status)){
   const action=active?'pause':'resume',button=element('button',active?'Tạm dừng':'Tiếp tục','secondary');
   button.disabled=uploading||(!active&&(current.busy||!current.account.plan_enabled));
   button.onclick=async()=>{button.disabled=true;try{await api(`/api/films/${encodeURIComponent(job.id)}/${action}`,{method:'POST'});notice('');await refresh();}catch(e){notice(e.message);button.disabled=false;}};actions.append(button);
  }
  card.append(actions);$('jobs').append(card);
 }
}
async function refresh(){
 if(polling)return;polling=true;
 try{
  current=await api('/api/state');const a=current.account;
  $('account-name').textContent=a.connected?(a.email||'Tài khoản ChatGPT đã kết nối'):'Kết nối ChatGPT để bắt đầu';
  $('account-status').textContent=a.error||(a.retry_seconds?`Đang chờ ChatGPT ${a.retry_seconds} giây trước khi gửi lại.`:a.plan_enabled?'Đang dùng gói ChatGPT đã cấp quyền cho app.':a.connected?'Đã đăng nhập; chưa được cấp quyền sử dụng gói.':'Đăng nhập lần đầu và cho phép app sử dụng gói ChatGPT của bạn.');
  $('login').textContent=a.connected?'Kết nối lại ChatGPT':'Continue with ChatGPT';$('login').disabled=current.busy||uploading;
  $('logout').hidden=!a.connected;$('logout').disabled=current.busy||uploading;
  const options=a.models||[];const signature=JSON.stringify(options);
  if($('model').dataset.options!==signature){$('model').replaceChildren(...options.map(m=>{const o=element('option',m.name);o.value=m.id;return o;}));$('model').dataset.options=signature;}
  $('model').value=a.model;$('model').disabled=current.busy||uploading||!options.length;
  $('source').disabled=current.busy||uploading;
  $('file').disabled=!a.plan_enabled||!a.model||!options.length||current.busy||uploading||!current.media_ready;
  $('drop').classList.toggle('disabled',$('file').disabled);
  $('data-location').textContent='Nơi lưu phim và tiến độ: '+current.data_location;
  renderJobs(current.jobs||[]);
  if(!current.media_ready)notice('Chưa tìm thấy bộ xử lý video trên máy.');
  if(a.plan_enabled&&!options.length&&!loadingModels&&!modelLoadAttempted&&!current.busy){
   loadingModels=true;modelLoadAttempted=true;
   try{await api('/api/account/models',{method:'POST'});}catch(e){notice(e.message);}finally{loadingModels=false;}
  }
 }catch(e){notice(e.message);}finally{polling=false;}
}
$('login').onclick=async()=>{
 const target=window.open('about:blank','chatgpt-personal-sign-in');
 try{const data=await api('/api/account/login',{method:'POST'});modelLoadAttempted=false;notice('');if(target)target.location.href=data.url;else{const a=element('a','Mở trang đăng nhập ChatGPT');a.href=data.url;a.target='_blank';a.rel='noopener noreferrer';notice('Trình duyệt chưa mở trang đăng nhập. ');$('notice').append(a);}}
 catch(e){if(target)target.close();notice(e.message);}
};
$('logout').onclick=async()=>{try{const result=await api('/api/account/logout',{method:'POST'});notice(result.revoked?'Đã ngắt kết nối.':'Đã ngắt trên máy; chưa xác nhận thu hồi phiên từ xa. Bạn có thể ngắt app trong phần cài đặt ChatGPT.');modelLoadAttempted=false;await refresh();}catch(e){notice(e.message);}};
$('model').onchange=async()=>{try{await api('/api/account/model',{method:'POST',body:JSON.stringify({model:$('model').value})});await refresh();}catch(e){notice(e.message);}};
function upload(file){
 if(!file||$('file').disabled)return;uploading=true;notice('');$('file').disabled=true;$('upload-status').hidden=false;$('upload-progress').value=0;
 const xhr=new XMLHttpRequest();xhr.open('POST','/api/films?source='+encodeURIComponent($('source').value));if(token)xhr.setRequestHeader('Authorization','Bearer '+token);xhr.setRequestHeader('X-Video-Name',encodeURIComponent(file.name));xhr.setRequestHeader('Content-Type','application/octet-stream');xhr.responseType='json';
 xhr.upload.onprogress=e=>{if(e.lengthComputable)$('upload-progress').value=e.loaded/e.total*100;};
 xhr.onload=()=>{if(xhr.status<200||xhr.status>=300)notice(xhr.response?.error||xhr.response?.detail||'Chưa nhận được phim.');};
 xhr.onerror=()=>notice('Kết nối tới app bị ngắt khi nhận phim. Hãy chọn lại tệp.');
 xhr.onloadend=()=>{uploading=false;$('file').value='';$('upload-status').hidden=true;refresh();};xhr.send(file);
}
$('file').onchange=e=>upload(e.target.files[0]);
$('close-app').onclick=async()=>{try{await api('/api/close',{method:'POST'});notice('Đã yêu cầu thoát app và lưu tiến độ. Bạn có thể đóng cửa sổ này.');clearInterval(pollTimer);$('file').disabled=true;$('login').disabled=true;}catch(e){notice(e.message);}};
for(const event of ['dragover','dragleave','drop'])$('drop').addEventListener(event,e=>{e.preventDefault();$('drop').classList.toggle('drag',event==='dragover');if(event==='drop')upload(e.dataTransfer.files[0]);});
let pollTimer;
async function initialize(){
 try{if(token){await api('/api/session',{method:'POST'});token='';history.replaceState(null,'',location.pathname);}await refresh();pollTimer=setInterval(refresh,1800);}
 catch(e){notice(e.message);}
}
initialize();
