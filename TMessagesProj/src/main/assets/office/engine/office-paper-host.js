/* Modifications Copyright (C) 2026 Ettacent */

"use strict";
(function(global){
  var tracked=new Map(),closed=false,nativePaused=false,attached=false,opened=false;
  var script=document.currentScript,relative=script?.dataset.paperWorker||"engine/office-paper-worker.js";
  global.OfficePaperWorkerURL=new URL(relative,document.baseURI).href;
  function controls(){var value={type:"office-paper-control",closed:closed,paused:nativePaused||document.hidden};global.OfficePaperNativePaused=nativePaused;
    if(closed)global.OfficePaperConsumer?.close();else global.OfficePaperConsumer?.pause(value.paused);
    global.OfficePptxPaper?.control?.(value);
    for(var[worker,channel]of tracked)try{worker.postMessage({...value,officePaperRPC:1,channel:channel});}catch(_){tracked.delete(worker);}
  }
  var Base=global.Worker;
  if(typeof Base==="function")global.Worker=class extends Base{
    constructor(...args){super(...args);this.addEventListener("message",event=>{
      var m=event.data;if(m?.officePaperRPC!==1)return;event.stopImmediatePropagation();
      if(m.type==="office-paper-runtime-ready"){if(typeof m.channel!=="string"||m.channel.length>128||tracked.has(this))return;tracked.set(this,m.channel);this.postMessage({officePaperRPC:1,type:"office-paper-control",channel:m.channel,closed:closed,paused:nativePaused||document.hidden});return;}
      if(m.type!=="office-paper-source-request"||tracked.get(this)!==m.channel||!Number.isInteger(m.rpcId)||m.rpcId<1||m.rpcId>32768)return;
      var service=global.OfficePaperConsumer;if(closed||!service)return;
      Promise.resolve(service.blob({path:m.path,mime:m.mime,blob:m.blob,palette:m.palette,current:()=>!closed&&tracked.has(this)})).then(result=>{
        if(!closed&&tracked.get(this)===m.channel)this.postMessage({officePaperRPC:1,type:"office-paper-source-reply",channel:m.channel,rpcId:m.rpcId,result:result});
      }).catch(()=>{if(!closed&&tracked.get(this)===m.channel)this.postMessage({officePaperRPC:1,type:"office-paper-source-reply",channel:m.channel,rpcId:m.rpcId,result:{status:"preserve"}});});
    },true);}
    terminate(){tracked.delete(this);return super.terminate();}
  };
  var blobs=new Map(),held=0,make=URL.createObjectURL.bind(URL),revoke=URL.revokeObjectURL.bind(URL);
  URL.createObjectURL=function(blob){var url=make(blob);if(/^image\/(png|jpeg)$/.test(blob.type)&&blob.size<=2097152&&blobs.size<64&&blob.size<=16777216-held){blobs.set(url,blob);held+=blob.size;}return url;};
  URL.revokeObjectURL=function(url){var blob=blobs.get(url);if(blob){held-=blob.size;blobs.delete(url);}return revoke(url);};
  global.OfficePaperHost=Object.freeze({
    source:function(url){return blobs.get(url)||null;},
    attach:function(api){
      if(attached)return global.OfficeViewer;attached=true;
      global.OfficeViewer=Object.freeze({...api,command:function(message){
        if(message?.type==='open'){
          if(opened){closed=true;controls();tracked.clear();global.OfficePaperConsumer=global.OfficePaperCreateConsumer(global);closed=false;}
          opened=true;
        }
        if(["close","cancel","dispose"].includes(message?.type))closed=true;
        if(message?.type==="pause")nativePaused=true;else if(message?.type==="resume")nativePaused=false;
        if(["open","pause","resume","close","cancel","dispose"].includes(message?.type))controls();return api.command(message);
      }});return global.OfficeViewer;
    }
  });
  document.addEventListener("visibilitychange",controls);global.addEventListener("pagehide",function(){closed=true;controls();blobs.clear();held=0;},{once:true});
})(globalThis);
