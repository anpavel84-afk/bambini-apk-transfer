const frame=document.getElementById('frame'),splash=document.getElementById('splash'),fill=document.getElementById('fill'),clock=document.getElementById('clock');
const DEFAULT_UI_CONFIG={uiVersion:0,dividerPx:4,dividerColor:'#000000',showClock:true,videoLayout:'accordion',accordionMs:700,activePanelGrow:1.35,sidePanelGrow:.825};
let UI_CONFIG={...DEFAULT_UI_CONFIG};
try{UI_CONFIG={...DEFAULT_UI_CONFIG,...JSON.parse(App.uiConfig()||'{}')}}catch(_){}
document.documentElement.style.setProperty('--divider-px',Math.max(0,Number(UI_CONFIG.dividerPx)||0)+'px');
document.documentElement.style.setProperty('--divider-color',String(UI_CONFIG.dividerColor||'#000000'));
document.documentElement.style.setProperty('--accordion-ms',Math.max(100,Number(UI_CONFIG.accordionMs)||700)+'ms');
document.documentElement.style.setProperty('--active-grow',String(Math.max(1,Number(UI_CONFIG.activePanelGrow)||1.35)));
document.documentElement.style.setProperty('--side-grow',String(Math.max(.1,Number(UI_CONFIG.sidePanelGrow)||.825)));
clock.style.display=UI_CONFIG.showClock===false?'none':'block';
let queue=[],running=false,current=null,starterDone=false,refilling=false,starterId=null;
let navRequest=null,navWake=null,activeAbort=null;
let sceneHistory=[],sceneFuture=[];
function diag(event,detail=''){try{console.info('[Bambini]',event,detail);App.diag(String(event),typeof detail==='string'?detail:JSON.stringify(detail))}catch(_){}}
window.addEventListener('error',e=>diag('WINDOW_ERROR',{message:e.message,source:e.filename,line:e.lineno,col:e.colno}));
window.addEventListener('unhandledrejection',e=>diag('UNHANDLED_REJECTION',String(e.reason)));
diag('SCRIPT_START',{href:location.href,ua:navigator.userAgent});
function hideSplash(){if(splash.dataset.hidden)return;splash.dataset.hidden='1';diag('SPLASH_HIDE');splash.style.opacity='0';setTimeout(()=>splash.style.display='none',600)}
const PREFETCH_AHEAD=5;
const PHOTO_SECONDS=8;
function updateClock(){
 const d=new Date();
 clock.textContent=String(d.getHours()).padStart(2,'0')+':'+String(d.getMinutes()).padStart(2,'0');
}
updateClock();setInterval(updateClock,15000);

const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function takeNav(){const n=navRequest;navRequest=null;return n}
function waitForNav(ms){return new Promise(resolve=>{
 let done=false,timer=null;
 const finish=v=>{if(done)return;done=true;if(timer)clearTimeout(timer);if(navWake===wake)navWake=null;resolve(v)};
 const wake=()=>finish(takeNav());
 navWake=wake;timer=setTimeout(()=>finish(null),ms);
})}
window.bambiniRemoteNavigate=dir=>{
 let action=dir==='left'?'prev':'next';
 if(action==='prev'&&!sceneHistory.length){diag('REMOTE_NAV_EMPTY',{dir,history:0});return false}
 if(action==='next'&&sceneFuture.length)action='forward';
 navRequest=action;
 diag('REMOTE_NAV',{dir,action,history:sceneHistory.length,future:sceneFuture.length});
 if(activeAbort){const fn=activeAbort;activeAbort=null;try{fn()}catch(_){}}
 if(navWake){const fn=navWake;navWake=null;try{fn()}catch(_){}}
 return true;
};
window.addEventListener('keydown',e=>{
 const c=e.keyCode||e.which||0;
 if(e.key==='ArrowLeft'||c===21||c===37){e.preventDefault();window.bambiniRemoteNavigate('left')}
 else if(e.key==='ArrowRight'||c===22||c===39){e.preventDefault();window.bambiniRemoteNavigate('right')}
},true);
function warmStatus(){try{return JSON.parse(App.warmStatus()||'{}')}catch(_){return {}}}
function warmIds(){try{return JSON.parse(App.warmIds()||'[]')}catch(_){return []}}
function mediaPath(p,id){
 try{if(App.isWarm(String(id)))return '/warm/'+id+'/poster.jpg'}catch(_){}
 const m=(p||'').match(/^\/(poster|thumb)\/(\d+)$/);
 if(m)return '/media/'+m[1]+'/'+m[2];
 return '/media/poster/'+id;
}
function thumbPath(id){return '/media/thumb/'+id}
function hlsPath(id){return '/stream/'+id+'/index.m3u8'}
function dateText(x){if(/^\d{4}-\d{2}-\d{2}$/.test(x||'')){const a=x.split('-');return a[2]+'.'+a[1]+'.'+a[0]}return x||''}
function videoIds(scene){return scene.items.filter(x=>x.type==='video').map(x=>String(x.id))}

function prefetchScenes(){
 let n=0;
 for(const s of queue){
  for(const id of videoIds(s)){
   if(n>=PREFETCH_AHEAD)return;
   diag('PREFETCH_REQUEST',{id,slot:n});
   try{App.prefetchVideo(id)}catch(e){diag('PREFETCH_BRIDGE_ERROR',{id,error:String(e)})}
   n++;
  }
 }
}

function videoStates(scene){
 return videoIds(scene).map(id=>({id,state:(()=>{try{return App.videoState(id)}catch(_){return 'LOADING'}})()}));
}

function pickNextSceneIndex(){
 if(!queue.length)return -1;
 const s=queue[0],states=videoStates(s);
 if(states.some(x=>x.state==='FAILED')){
  diag('SCENE_DROP_FAILED',{kind:s.kind,ids:states.map(x=>x.id)});
  queue.shift();
  return pickNextSceneIndex();
 }
 return 0;
}

function disposeNode(node){
 try{(node?._cleanups||[]).forEach(fn=>{try{fn()}catch(_){}})}catch(_){}
 try{node?.remove()}catch(_){}
}

function refill(){
 if(refilling||queue.length>8)return;
 refilling=true;
 setTimeout(()=>{
  try{
   const obj=JSON.parse(App.queue()||'{}');
   if(obj.scenes&&obj.scenes.length){queue.push(...obj.scenes);diag('QUEUE_APPEND',{added:obj.scenes.length,total:queue.length,kinds:obj.scenes.map(x=>x.kind)})}
   else diag('QUEUE_EMPTY',obj);
  }catch(_){}
  refilling=false;
  prefetchScenes();
  if(starterDone&&!running)advance();
 },0);
}

function loadImageWithFallback(item,forVideo){
 return new Promise(resolve=>{
  const img=new Image();
  img.className='visual '+(forVideo?'poster':'photo');
  img.alt='';
  let triedThumb=false,finished=false;
  const done=ok=>{if(finished)return;finished=true;resolve({img,ok})};
  img.onload=()=>{diag('IMAGE_OK',{id:item.id,src:img.src,natural:[img.naturalWidth,img.naturalHeight]});done(true)};
  img.onerror=()=>{
   if(!triedThumb){triedThumb=true;img.src=thumbPath(item.id);return}
   diag('IMAGE_FAIL',{id:item.id,src:img.src});done(false);
  };
  img.src=mediaPath(item.poster,item.id);
  setTimeout(()=>done(false),7000);
 });
}

async function build(scene){
 diag('SCENE_BUILD',{kind:scene.kind,ids:scene.items.map(x=>x.id),types:scene.items.map(x=>x.type)});
 const node=document.createElement('section');
 node.className='scene';
 node.dataset.kind=scene.kind;
 node._cleanups=[];
 if(UI_CONFIG.videoLayout==='accordion' && scene.items.length===3 && scene.items.every(x=>x.type==='video'))node.classList.add('video-triple');

 const panelData=[];
 for(let i=0;i<scene.items.length;i++){
  const item=scene.items[i];
  const panel=document.createElement('div');
  panel.className='panel';
  panel.dataset.index=i;
  if(scene.kind==='SAME_PERSON_VIDEO_TRIPLE'&&i===1)panel.classList.add('subdued');

  const loaded=await loadImageWithFallback(item,item.type==='video');
  panel.append(loaded.img);

  panelData.push({panel,item,poster:loaded.img,posterOk:loaded.ok,sceneNode:node});
 }

 if(scene.kind==='PORTRAIT_VIDEO_CINEMATIC'&&panelData.length===1){
  node.classList.add('cinematic');
  const bg=panelData[0].poster.cloneNode();
  bg.className='backdrop';
  node.append(bg);
 }
 panelData.forEach(x=>node.append(x.panel));
 return {node,panelData};
}

function waitReady(id,maxMs=18000){
 return new Promise(resolve=>{
  const start=Date.now();
  const check=()=>{
   try{
    const state=App.videoState(String(id));
    if(state==='READY'){diag('VIDEO_READY',{id});return resolve(true)}
    if(state==='FAILED'){diag('VIDEO_FAILED',{id});return resolve(false)}
   }catch(_){}
   if(Date.now()-start>=maxMs){diag('VIDEO_READY_TIMEOUT',{id,maxMs});return resolve(false)}
   setTimeout(check,180);
  };
  check();
 });
}

function fallbackPoster(panelData,ms=4500){
 return sleep(ms);
}

async function playHls(item,panelData,onReveal){
 diag('VIDEO_WAIT',{id:item.id});
 const initialReady=(()=>{try{return App.videoState(String(item.id))==='READY'}catch(_){return false}})();
 if(!initialReady)diag('VIDEO_POSTER_BUFFERING',{id:item.id});
 return new Promise(resolve=>{
  const panel=panelData.panel,poster=panelData.poster;
  let leased=false;
  try{App.leaseVideo(String(item.id));leased=true}catch(_){}
  const v=document.createElement('video');
  v.className='visual';
  v.playsInline=true;
  v.autoplay=false;
  v.preload='auto';
  v.controls=false;
  v.volume=1;
  v.muted=false;
  panel.append(v);

  let hls=null,finished=false,started=false,beat=null,startTimer=null,hardTimer=null,promoted=false;
  const cleanup=()=>{
   clearTimeout(startTimer);clearTimeout(hardTimer);clearInterval(beat);
   if(hls){try{hls.destroy()}catch(_){}hls=null}
   try{v.pause()}catch(_){}
   try{v.remove()}catch(_){}
   if(leased){leased=false;try{App.releaseVideo(String(item.id))}catch(_){}}
  };
  panelData.sceneNode._cleanups.push(cleanup);

  const promote=()=>{
   if(promoted||!started)return;
   promoted=true;
   try{App.promoteWarm(String(item.id))}catch(_){}
  };
  let abort=null;
  const finish=(keepFrame=true)=>{
   if(finished)return;
   finished=true;
   if(activeAbort===abort)activeAbort=null;
   clearTimeout(startTimer);clearTimeout(hardTimer);clearInterval(beat);
   promote();
   if(!started||!keepFrame)cleanup();
   else try{v.pause()}catch(_){}
   resolve(started);
  };
  abort=()=>finish(false);
  activeAbort=abort;
  const reveal=()=>{
   if(started)return;
   started=true;
   diag('VIDEO_PLAYING',{id:item.id,readyState:v.readyState,width:v.videoWidth,height:v.videoHeight,time:v.currentTime,muted:v.muted,volume:v.volume});
   beat=setInterval(()=>diag('VIDEO_HEARTBEAT',{id:item.id,time:Number(v.currentTime.toFixed(2)),readyState:v.readyState,paused:v.paused}),2000);
   if(onReveal)try{onReveal()}catch(_){}
   v.classList.add('playing');
   requestAnimationFrame(()=>requestAnimationFrame(()=>{
    poster.style.opacity='0';
    setTimeout(()=>poster.style.visibility='hidden',320);
   }));
  };

  const startSource=()=>{
  const source=hlsPath(item.id);
  if(Hls.isSupported()){
   hls=new Hls({maxBufferLength:45,maxMaxBufferLength:75,backBufferLength:5,enableWorker:true,startFragPrefetch:true,fragLoadingTimeOut:30000});
   hls.loadSource(source);
   hls.attachMedia(v);
   hls.on(Hls.Events.MANIFEST_PARSED,(_,d)=>{diag('HLS_MANIFEST',{id:item.id,levels:d.levels?.length||0});v.play().catch(e=>{diag('VIDEO_PLAY_REJECT',{id:item.id,error:String(e)});finish(false)})});
   hls.on(Hls.Events.ERROR,(_,d)=>{diag('HLS_ERROR',{id:item.id,type:d.type,details:d.details,fatal:d.fatal,response:d.response?.code});if(d.fatal)finish(started)});
  }else{
   v.src=source;
   v.play().catch(e=>{diag('VIDEO_NATIVE_PLAY_REJECT',{id:item.id,error:String(e)});finish(false)});
  }

  v.addEventListener('loadeddata',()=>diag('VIDEO_LOADEDDATA',{id:item.id,readyState:v.readyState}));
  v.addEventListener('canplay',()=>diag('VIDEO_CANPLAY',{id:item.id,readyState:v.readyState}));
  v.addEventListener('playing',reveal);
  v.addEventListener('waiting',()=>diag('VIDEO_WAITING',{id:item.id,time:v.currentTime,readyState:v.readyState}));
  v.addEventListener('stalled',()=>diag('VIDEO_STALLED',{id:item.id,time:v.currentTime,readyState:v.readyState}));
  v.addEventListener('ended',()=>{diag('VIDEO_ENDED',{id:item.id,time:v.currentTime});finish(true)});
  v.addEventListener('error',()=>{diag('VIDEO_ELEMENT_ERROR',{id:item.id,code:v.error?.code,message:v.error?.message});finish(started)});

  startTimer=setTimeout(()=>{if(!started){diag('VIDEO_START_TIMEOUT',{id:item.id});finish(false)}},12000);
  hardTimer=setTimeout(()=>{diag('VIDEO_HARD_TIMEOUT',{id:item.id,time:v.currentTime,started});finish(started)},((Math.max(8,item.limit||80))+35)*1000);
  };
  if(initialReady)startSource();
  else waitReady(item.id,12000).then(ok=>{
   if(finished)return;
   if(ok)startSource();
   else{diag('VIDEO_BUFFER_TIMEOUT',{id:item.id});finish(false)}
  });
 });
}

function activateVideoPanel(built,idx){
 if(!built?.node?.classList.contains('video-triple'))return;
 built.panelData.forEach((x,j)=>{
  const active=j===idx;
  x.panel.classList.toggle('active',active);
  x.panel.classList.toggle('inactive',!active);
  if(active){
   const w=Number(x.item.width)||9,h=Number(x.item.height)||16;
   const aspect=Math.max(.3,Math.min(1.2,w/h));
   x.panel.style.setProperty('--media-aspect',String(aspect));
  }
 });
 diag('ACCORDION_ACTIVE',{index:idx,id:built.panelData[idx]?.item?.id,aspect:built.panelData[idx]?.panel?.style?.getPropertyValue('--media-aspect')});
}

async function playScene(scene,built,onFirstPlaying){
 const vids=scene.items.map((x,i)=>x.type==='video'?i:-1).filter(i=>i>=0);
 if(!vids.length){
  diag('PHOTO_HOLD',{kind:scene.kind,ms:PHOTO_SECONDS*1000});
  const nav=await waitForNav(PHOTO_SECONDS*1000);
  return {allStarted:true,nav:nav||takeNav()};
 }
 let allStarted=true;
 for(let k=0;k<vids.length;k++){
  const idx=vids[k];
  activateVideoPanel(built,idx);
  if(scene.kind==='SAME_PERSON_VIDEO_TRIPLE'){
   built.panelData.forEach((x,j)=>x.panel.classList.toggle('subdued',j!==idx));
  }
  const started=await playHls(scene.items[idx],built.panelData[idx],k===0?onFirstPlaying:null);
  const immediate=takeNav();if(immediate)return {allStarted,nav:immediate};
  if(!started){allStarted=false;diag('VIDEO_ITEM_SKIPPED',{id:scene.items[idx].id,kind:scene.kind});break}
  const nav=await waitForNav(350);if(nav)return {allStarted,nav};
 }
 return {allStarted,nav:null};
}

async function transitionTo(node,old,meta={}){
 frame.append(node);
 await sleep(34);
 node.classList.add('visible');
 if(old)old.classList.add('leaving');
 diag('TRANSITION_DISSOLVE',{from:old?.dataset?.kind||null,to:node.dataset.kind,...meta});
 await sleep(1350);
 if(old)disposeNode(old);
 current=node;
}

async function advance(){
 if(running||!starterDone)return;
 if(!queue.length){refill();return}
 running=true;
 let forcedScene=null,forcedMode='normal';

 while(true){
  let scene,states,replay=false;
  if(forcedScene){
   scene=forcedScene;forcedScene=null;replay=true;states=videoStates(scene);
  }else{
   if(!queue.length){refill();await sleep(300);if(!queue.length)break}
   prefetchScenes();
   const idx=pickNextSceneIndex();
   if(idx<0){refill();await sleep(250);continue}
   scene=queue.splice(idx,1)[0];
   states=videoStates(scene);
   refill();prefetchScenes();
  }

  diag('SCENE_BEGIN',{kind:scene.kind,ids:scene.items.map(x=>x.id),states,queueLeft:queue.length,mode:forcedMode});
  let built;
  try{built=await build(scene)}catch(e){diag('SCENE_BUILD_ERROR',{kind:scene.kind,error:String(e)});forcedMode='normal';continue}
  const node=built.node,old=current,vids=videoIds(scene);

  await transitionTo(node,old,{video:!!vids.length,readyAtTransition:vids.length?states.every(x=>x.state==='READY'):undefined,mode:forcedMode});
  hideSplash();
  if(!replay)try{App.played(JSON.stringify(scene.items.map(x=>x.id)))}catch(_){}
  const result=await playScene(scene,built);
  if(vids.length&&!result.allStarted&&!result.nav)diag('SCENE_VIDEO_FAIL',{kind:scene.kind,ids:vids});
  const nav=result.nav||takeNav();
  diag('SCENE_END',{kind:scene.kind,ids:scene.items.map(x=>x.id),nav});

  if(nav==='prev'&&sceneHistory.length){
   sceneFuture.push(scene);
   forcedScene=sceneHistory.pop();
   forcedMode='back';
   continue;
  }
  if(nav==='forward'&&sceneFuture.length){
   sceneHistory.push(scene);
   if(sceneHistory.length>30)sceneHistory.splice(0,sceneHistory.length-30);
   forcedScene=sceneFuture.pop();
   forcedMode='forward';
   continue;
  }

  sceneHistory.push(scene);
  if(sceneHistory.length>30)sceneHistory.splice(0,sceneHistory.length-30);
  sceneFuture.length=0;
  forcedMode='normal';
 }
 running=false;
 refill();
}

async function playStarter(){
 const ids=warmIds();
 diag('STARTER_IDS',{ids});
 if(!ids.length)return false;
 const shuffled=[...ids].sort(()=>Math.random()-.5);
 for(const id of shuffled){
  starterId=id;
  diag('STARTER_PICK',{id:starterId});
  const fake={id:Number(starterId),limit:80,poster:'/poster/'+starterId,type:'video',person:'',date:''};
  const built=await build({kind:'LANDSCAPE_VIDEO',items:[fake]});
  const node=built.node;
  frame.append(node);
  let firstResolve;
  const first=new Promise(r=>firstResolve=r);
  const playPromise=playHls(fake,built.panelData[0],()=>firstResolve(true));
  const started=await Promise.race([first,playPromise.then(()=>false),sleep(6500).then(()=>false)]);
  if(!started){
   diag('STARTER_FAIL',{id:starterId});
   await playPromise.catch(()=>false);
   disposeNode(node);
   continue;
  }
  node.classList.add('visible');
  current=node;
  hideSplash();
  try{App.played(JSON.stringify([Number(starterId)]))}catch(_){}
  await playPromise;
  starterDone=true;
  advance();
  return true;
 }
 diag('STARTER_ALL_FAILED',{ids});
 return false;
}

let lastStartupSig='';
function startup(){
 const s=warmStatus(),ids=warmIds();
 fill.style.width=(s.percent||0)+'%';
 const sig=[s.stage,s.id,s.percent,ids.length,s.error].join('|');if(sig!==lastStartupSig){lastStartupSig=sig;diag('STARTUP_STATUS',{stage:s.stage,id:s.id,percent:s.percent,ready:ids.length,error:s.error})}
 if(ids.length){
  playStarter();
  refill();
  return;
 }
 if(queue.length){
  diag('START_WITH_QUEUE',{scenes:queue.length});
  starterDone=true;
  advance();
  return;
 }
 setTimeout(startup,120);
}

refill();
startup();
try{App.uiReady(String(UI_CONFIG.uiVersion||''))}catch(e){diag('UI_READY_BRIDGE_ERROR',String(e))}
setInterval(refill,12000);