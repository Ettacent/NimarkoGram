/* Modifications Copyright (C) 2026 Ettacent */

"use strict";

var OfficePaperSession=(function(){
  function create(owner){
    if(!owner||["run","live","epoch","paused","current"].some(function(k){return typeof owner[k]!=="function";}))throw new TypeError("Owner contract");
    var epoch=owner.epoch(),closed=false,sources=new Map(),pixels=0,bytes=0,derived=0;
    function live(){return !closed&&owner.live()&&owner.epoch()===epoch;}
    function original(source,status,reason){return {status:status||"preserve",reason:reason||0,blob:source.blob,width:source.width,height:source.height,variant:"original"};}
    function status(source,options,palette){
      if(!live()||options?.signal?.aborted||palette!==undefined&&!owner.current(palette))return original(source,"cancelled",1);
      if(owner.paused())return original(source,"paused",0);
      return null;
    }
    function admit(id,source){
      if(!live()||!Number.isInteger(id)||id<1||id>12||!source||source.provenance!=="archive-raster"||!(source.blob instanceof Blob))return false;
      if(sources.has(id)){var s=sources.get(id);return s.blob===source.blob&&s.width===source.width&&s.height===source.height;}
      var w=source.width,h=source.height,n=w*h;
      if(sources.size>=12||!Number.isInteger(w)||!Number.isInteger(h)||w<8||h<8||w>2048||h>2048||n>350000||n>1000000-pixels||source.blob.size<10||source.blob.size>2097152||source.blob.size>4194304-bytes)return false;
      sources.set(id,{blob:source.blob,width:w,height:h,prepared:null,pending:null,night:null,composing:null,request:0});pixels+=n;bytes+=source.blob.size;return true;
    }
    function valid(result,s){return result&&result.type===2&&result.width===s.width&&result.height===s.height&&typeof result.accepted==="boolean";}
    function output(result){return result.blob instanceof Blob&&result.blob.size>0&&result.blob.size<=2100000&&result.blob.type==="image/png";}
    function ensure(s){
      if(s.prepared||s.pending)return s.pending||Promise.resolve();
      var task=(async function(){
        try{
          var result=await owner.run({op:"prepare",provenance:"archive-raster",blob:s.blob,w:s.width,h:s.height});
          if(!live())return;
          if(result?.status==="paused"||result?.status==="cancelled")return;
          if(!valid(result,s))throw new Error("result");
          if(result.accepted){
            if(!output(result)||!Array.isArray(result.exposure)||result.exposure.length!==32||result.exposure.some(function(n){return !Number.isInteger(n)||n<0||n>s.width*s.height;})||result.exposure.reduce(function(a,b){return a+b;},0)>s.width*s.height)throw new Error("matte");
            if(result.darkInkRevision!==1||!Number.isInteger(result.inkPixels)||result.inkPixels<0||result.inkPixels>s.width*s.height||
              !Array.isArray(result.inkMin)||result.inkMin.length!==3||result.inkMin.some(function(v){return !Number.isInteger(v)||v<0||v>255;})||
              (result.inkPixels? !output({blob:result.inkBlob}):result.inkBlob!==null))throw new Error("ink layer");
            var size=result.blob.size+(result.inkBlob?.size||0);
            if(size>6291456-derived){s.prepared={accepted:false,reason:21};return;}
            derived+=size;s.prepared={accepted:true,blob:result.blob,inkBlob:result.inkBlob,inkMin:Object.freeze(result.inkMin.slice()),exposure:Object.freeze(result.exposure.slice())};
          }else s.prepared={accepted:false,reason:result.reason||20};
        }catch(error){
          if(live()&&!owner.paused()&&error?.name!=="AbortError")s.prepared={accepted:false,reason:22};
        }
      })();
      s.pending=task;task.finally(function(){if(s.pending===task)s.pending=null;});return task;
    }
    async function prepare(id,options){
      var s=sources.get(id);if(!s)return {status:live()?"preserve":"cancelled",reason:1};
      var stopped=status(s,options);if(stopped)return stopped;
      await ensure(s);stopped=status(s,options);if(stopped)return stopped;
      return s.prepared?.accepted?{status:"prepared"}:original(s,"preserve",s.prepared?.reason||24);
    }
    function compose(s,plan){
      var revision=++s.request;
      var task=(async function(){try{
        var rgb=[1,3,5].map(function(at){return parseInt(plan.colour.slice(at,at+2),16);});
        var result=await owner.run({op:"compose",provenance:"prepared-matte",blob:s.prepared.blob,inkBlob:s.prepared.inkBlob,inkMin:s.prepared.inkMin,w:s.width,h:s.height,rgb:rgb,
          gray:rgb[0]===rgb[1]&&rgb[1]===rgb[2]?rgb[0]:undefined});
        if(!live()||revision!==s.request)return;
        if(!valid(result,s)||!result.accepted||!output(result))throw new Error("composite");
        var prior=s.night?.blob.size||0;if(result.blob.size>6291456-derived+prior)return;
        derived+=result.blob.size-prior;s.night={key:plan.colour,blob:result.blob};
      }catch(_){} })();
      s.composing=task;task.finally(function(){if(s.composing===task)s.composing=null;});return task;
    }
    async function select(id,paperL,palette,options){
      if(Array.isArray(options?.paperRGB))options={...options,paperRGB:options.paperRGB.slice()};
      var s=sources.get(id);if(!s)return {status:live()?"preserve":"cancelled",reason:1};
      var stopped=status(s,options,palette);if(stopped)return stopped;
      var prepared=await prepare(id,options);if(prepared.status!=="prepared")return prepared;
      stopped=status(s,options,palette);if(stopped)return stopped;
      var rgb=options?.paperRGB;
      if(!Array.isArray(rgb)||rgb.length!==3||!rgb.every(function(v){return Number.isInteger(v)&&v>=0&&v<=255;}))return original(s,"preserve",25);
      var channels=rgb.map(function(v){v/=255;return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);});
      var actual=.2126*channels[0]+.7152*channels[1]+.0722*channels[2];
      if(!Number.isFinite(paperL)||Math.abs(paperL-actual)>1e-6||actual>.04)return original(s,"preserve",26);
      var plan={choice:3,paper:actual,colour:"#"+rgb.map(function(v){return v.toString(16).padStart(2,"0");}).join(""),backingKind:"dark-artwork",inkRevision:1};
      if(s.night?.key===plan.colour)return {status:"ready",blob:s.night.blob,width:s.width,height:s.height,variant:"backed",contrast:plan};
      if(s.composing){await s.composing;return select(id,paperL,palette,options);}
      await compose(s,plan);stopped=status(s,options,palette);if(stopped)return stopped;
      return s.night?.key===plan.colour?{status:"ready",blob:s.night.blob,width:s.width,height:s.height,variant:"backed",contrast:plan}:original(s,"preserve",22);
    }
    function close(){closed=true;owner.cancel?.();sources.clear();pixels=bytes=derived=0;}
    return Object.freeze({admit:admit,prepare:prepare,select:select,original:function(id){var s=sources.get(id);return s?original(s):null;},close:close,
      stats:function(){return {sources:sources.size,pixels:pixels,sourceBytes:bytes,derivedBytes:derived,closed:closed};}});
  }
  return Object.freeze({create:create});
})();
