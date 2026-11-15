/* fige.js — quand un jeu s'ouvre par-dessus un lieu, le lieu s'arrête complètement
   (plus d'animation, plus de minuteries, plus de dessin, vidéos et sons en pause),
   pour que le téléphone ne fasse tourner QUE le jeu. Il reprend à la sortie du jeu.
   À charger en tout premier dans le lieu :  <script src="/assets/shinato/fige.js"></script> */
(function(){
 var raf=window.requestAnimationFrame.bind(window), st=window.setTimeout.bind(window), si=window.setInterval.bind(window);
 var attente=[], caches=[], medias=[];
 window.LIEU_FIGE=false;
 window.requestAnimationFrame=function(cb){ if(window.LIEU_FIGE){ attente.push(function(){ raf(cb); }); return 0; } return raf(cb); };
 window.setTimeout=function(cb,ms){ var a=[].slice.call(arguments,2);
  return st(function(){ if(window.LIEU_FIGE) attente.push(function(){ if(typeof cb==='function') cb.apply(null,a); }); else if(typeof cb==='function') cb.apply(null,a); },ms); };
 window.setInterval=function(cb,ms){ var a=[].slice.call(arguments,2);
  return si(function(){ if(!window.LIEU_FIGE&&typeof cb==='function') cb.apply(null,a); },ms); };
 var css=document.createElement('style');
 css.textContent='html.lieuFige *{animation-play-state:paused!important;transition:none!important}';
 (document.head||document.documentElement).appendChild(css);
 // garde : l'élément du jeu (l'iframe) qui reste affiché ; tout le reste du lieu est caché
 window.figeLieu=function(garde){
  if(window.LIEU_FIGE) return; window.LIEU_FIGE=true;
  document.documentElement.classList.add('lieuFige');
  caches=[]; [].forEach.call(document.body.children,function(el){
   if(el===garde||(garde&&el.contains&&el.contains(garde))||el.tagName==='SCRIPT'||el.tagName==='STYLE') return;
   caches.push([el,el.style.visibility]); el.style.visibility='hidden'; });
  medias=[]; [].forEach.call(document.querySelectorAll('video,audio'),function(m){
   if(garde&&garde.contains&&garde.contains(m)) return;
   if(!m.paused){ medias.push(m); try{ m.pause(); }catch(e){} } });
 };
 window.repriseLieu=function(){
  if(!window.LIEU_FIGE) return; window.LIEU_FIGE=false;
  document.documentElement.classList.remove('lieuFige');
  caches.forEach(function(c){ c[0].style.visibility=c[1]; }); caches=[];
  medias.forEach(function(m){ try{ var q=m.play(); if(q&&q.catch) q.catch(function(){}); }catch(e){} }); medias=[];
  var a=attente; attente=[]; a.forEach(function(f){ try{ f(); }catch(e){} });
 };
})();
