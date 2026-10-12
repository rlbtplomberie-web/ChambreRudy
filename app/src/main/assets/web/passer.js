/* ---------- ⏩ Passer la vidéo : un petit bouton sur TOUTES les vidéos du jeu ----------
   Chargé par les pages principales (Shinato et toutes ses villes, la chambre, le téléphone, les visites…).
   Le bouton vit dans la page tout en haut : il passe donc AU-DESSUS de tout (argent, menus, cadres des villes,
   des missions et des mini-jeux). Il cherche les vidéos dans toutes les pages ouvertes à l'intérieur,
   et dès qu'une vidéo joue (en grand, dans le téléphone, sur la télé…), il se pose dans son coin haut droit.
   Un appui = la vidéo va directement à la fin, exactement comme si on l'avait regardée jusqu'au bout :
   la suite du jeu se déroule normalement. */
(function(){
  if(window.__passeurPrincipal) return;
  var haut=null; try{ haut=window.top; if(haut&&haut!==window) void haut.document; }catch(e){ haut=null; }
  if(haut&&haut!==window){
    // page ouverte dans un cadre : c'est la page du haut qui s'occupe du bouton (si elle l'a)
    setTimeout(function(){ var ok=false; try{ ok=!!haut.__passeurPrincipal; }catch(e){} if(!ok) demarre(); },1500);
    return;
  }
  demarre();

  function demarre(){
  if(window.__passeurPrincipal) return; window.__passeurPrincipal=true;
  var W0=window, doc0=document;
  var bt=null, cible=null, cibleWin=null, passee=null, derniere=null, caches=[];

  function bouton(){
    if(bt&&doc0.body&&doc0.body.contains(bt)) return bt;
    if(!doc0.body) return null;
    bt=doc0.createElement('div'); bt.setAttribute('aria-label','Passer la vidéo'); bt.setAttribute('data-passer','1');
    bt.innerHTML='<svg viewBox="0 0 24 24" width="60%" height="60%"><path d="M3 5l9 7-9 7z M12 5l9 7-9 7z" fill="#fff"/></svg>';
    bt.style.cssText='position:fixed;z-index:2147483647;left:0;top:10px;width:46px;height:46px;'+
      'display:none;align-items:center;justify-content:center;border-radius:50%;background:rgba(0,0,0,.55);box-sizing:border-box;'+
      'border:2px solid rgba(255,255,255,.75);box-shadow:0 2px 10px rgba(0,0,0,.6);cursor:pointer;touch-action:manipulation;-webkit-tap-highlight-color:transparent;pointer-events:auto';
    var t0=0;
    var go=function(e){ if(e){ e.preventDefault(); e.stopPropagation(); } var n=Date.now(); if(n-t0<500) return; t0=n; passe(); };
    bt.addEventListener('touchstart',go,{passive:false});
    bt.addEventListener('pointerdown',function(e){ e.stopPropagation(); });
    bt.addEventListener('mousedown',function(e){ e.stopPropagation(); });
    bt.addEventListener('click',go);
    doc0.body.appendChild(bt); return bt;
  }

  function passe(){
    var v=cible; if(!v) return;
    passee=v; var src=v.currentSrc||v.src, w=v.ownerDocument.defaultView||W0;
    try{
      if(isFinite(v.duration)&&v.duration>0){ v.muted=true; v.currentTime=Math.max(0,v.duration-0.05); var q=v.play(); if(q&&q.catch) q.catch(function(){}); }
    }catch(e){}
    // au cas où la vidéo ne finirait pas toute seule : on annonce la fin nous-mêmes
    setTimeout(function(){ try{ if(!v.ended&&(v.currentSrc||v.src)===src){ v.pause(); v.dispatchEvent(new w.Event('ended')); } }catch(e){} },900);
    setTimeout(function(){ try{ if(v.muted&&(v.currentSrc||v.src)!==src) v.muted=false; }catch(e){} },1500);
    if(bt) bt.style.display='none';
  }

  // toutes les pages visibles (la page du haut + chaque ville, mission, mini-jeu ouvert dedans), avec leur place à l'écran
  function pages(){
    var res=[];
    (function va(win,ox,oy,clip,prof){
      var doc; try{ doc=win.document; if(!doc||!doc.documentElement) return; }catch(e){ return; }
      res.push({win:win,doc:doc,ox:ox,oy:oy,clip:clip});
      if(prof>6) return;
      var fr; try{ fr=doc.querySelectorAll('iframe,frame'); }catch(e){ return; }
      for(var i=0;i<fr.length;i++){
        var f=fr[i], cw=null; try{ cw=f.contentWindow; void cw.document; }catch(e){ continue; }
        if(!cw) continue;
        var r=f.getBoundingClientRect(); if(r.width<2||r.height<2) continue;
        try{ var st=win.getComputedStyle(f); if(st.display==='none'||st.visibility==='hidden') continue; }catch(e){}
        var x=ox+r.left+f.clientLeft, y=oy+r.top+f.clientTop;
        var c={left:Math.max(clip.left,ox+r.left),top:Math.max(clip.top,oy+r.top),right:Math.min(clip.right,ox+r.right),bottom:Math.min(clip.bottom,oy+r.bottom)};
        if(c.right-c.left<2||c.bottom-c.top<2) continue;
        va(cw,x,y,c,prof+1);
      }
    })(W0,0,0,{left:0,top:0,right:W0.innerWidth||1,bottom:W0.innerHeight||1},0);
    return res;
  }

  // la partie visible d'une vidéo qui joue (à l'échelle de tout l'écran), ou rien
  function zoneVideo(v,p){
    try{
      if(v.loop||v.paused||v.ended) return null;
      if(v.id==='tvVid') return null;                 // la télé de la chambre : on n'y touche pas
      var st=p.win.getComputedStyle(v); if(st.visibility==='hidden'||st.display==='none'||+st.opacity===0) return null;
      var r=v.getBoundingClientRect();
      var z={left:Math.max(p.clip.left,p.ox+r.left),top:Math.max(p.clip.top,p.oy+r.top),right:Math.min(p.clip.right,p.ox+r.right),bottom:Math.min(p.clip.bottom,p.oy+r.bottom)};
      var w=z.right-z.left, h=z.bottom-z.top;
      if(w<40||h<40) return null;
      var W=W0.innerWidth||1, H=W0.innerHeight||1;
      if(w*h<0.015*W*H) return null;
      z.aire=w*h; return z;
    }catch(e){ return null; }
  }

  // ce qu'il y a à l'écran à cet endroit, en descendant dans les cadres
  function elementA(x,y){
    var win=W0, el=null, chaine=[];
    for(var k=0;k<8;k++){
      try{ el=win.document.elementFromPoint(x,y); }catch(e){ return {el:el,chaine:chaine}; }
      if(el&&(el.tagName==='IFRAME'||el.tagName==='FRAME')){
        var cw=null; try{ cw=el.contentWindow; void cw.document; }catch(e){ return {el:el,chaine:chaine}; }
        var r=el.getBoundingClientRect(); chaine.push(el);
        x-=r.left+el.clientLeft; y-=r.top+el.clientTop; win=cw; continue;
      }
      return {el:el,chaine:chaine};
    }
    return {el:el,chaine:chaine};
  }

  // les anciens boutons « Passer », « ✕ Fermer »… posés sur la vidéo : cachés tant que notre bouton est là
  var RE=/^\s*(passer|skip|fermer|[✕×x]\s*fermer|[✕×x])\s*\.{0,3}\s*$/i;
  function cacheAnciens(p,zone){
    var els; try{ els=p.doc.querySelectorAll('button,div,span,a'); }catch(e){ return; }
    for(var i=0;i<els.length;i++){
      var el=els[i]; if(el===bt||el.__passeCache!==undefined||el.getAttribute('data-passer')) continue;
      var t=(el.textContent||'').trim(); if(!t||t.length>14||!RE.test(t.replace(/[▶►⏩⏭]/g,'').trim())) continue;
      if(el.children.length>2) continue;
      var r=el.getBoundingClientRect(); if(r.width<4||r.height<4||r.width>280||r.height>100) continue;
      var L=p.ox+r.left, T=p.oy+r.top, R=p.ox+r.right, B=p.oy+r.bottom;
      if(R<zone.left-20||L>zone.right+20||B<zone.top-20||T>zone.bottom+20) continue;   // seulement ceux posés sur la vidéo
      el.__passeCache=el.style.display; el.style.display='none'; caches.push(el);
    }
  }
  function remetAnciens(){ caches.forEach(function(el){ try{ el.style.display=el.__passeCache||''; delete el.__passeCache; }catch(e){} }); caches=[]; }

  function tour(){
    var ps=pages(), best=0, zone=null, pg=null; cible=null;
    for(var j=0;j<ps.length;j++){
      var vs; try{ vs=ps[j].doc.getElementsByTagName('video'); }catch(e){ continue; }
      for(var i=0;i<vs.length;i++){ var z=zoneVideo(vs[i],ps[j]); if(z&&z.aire>best){ best=z.aire; zone=z; cible=vs[i]; pg=ps[j]; } }
    }
    if(passee&&cible===passee&&(cible.currentSrc||cible.src)&&cible.currentTime>=cible.duration-0.3) cible=null;   // déjà passée
    if(cible!==derniere){ if(caches.length) remetAnciens(); derniere=cible; }
    if(passee&&cible!==passee){ try{ if(passee.muted&&!passee.ended) passee.muted=false; }catch(e){} passee=null; }
    var b=bouton(); if(!b) return;
    if(!cible){ b.style.display='none'; return; }
    var W=W0.innerWidth||0, H=W0.innerHeight||0;
    var plein=best>=0.6*W*H;
    cacheAnciens(pg,zone);
    var petit=(zone.right-zone.left)<200||(zone.bottom-zone.top)<160, T=petit?34:46;
    b.style.width=b.style.height=T+'px';
    var x=Math.max(2,Math.min(W,zone.right)-(plein?10:6)-T), y=Math.max(zone.top,0)+(plein?10:6);
    b.style.left=x+'px'; b.style.top=y+'px';
    b.style.display='flex';
    // s'il y a déjà quelque chose à cet endroit (l'argent, ✕ Fermer, un menu…), le nôtre se met juste en dessous
    b.style.pointerEvents='none';
    var occupe=false, cx=x+T/2, cy=y+T/2;
    [[cx,cy],[cx-T/3,cy],[cx+T/3,cy],[cx,cy-T/3],[cx,cy+T/3]].forEach(function(pt){
      var a=elementA(pt[0],pt[1]), el=a.el;
      if(!el||el===cible) return;
      try{ if(el.contains(cible)) return; }catch(e){}
      var d=el.ownerDocument;
      if(d===pg.doc){ occupe=true; return; }                       // un bouton dans la même page que la vidéo
      if(el===d.documentElement||el===d.body) return;
      occupe=true;                                                   // un élément d'une page par-dessus (argent, menu…)
    });
    b.style.pointerEvents='auto';
    if(occupe){ var y2=y+T+8; if(y2+T<H) b.style.top=y2+'px'; }
  }

  // dès qu'une vidéo démarre (dans n'importe quelle page), le bouton arrive tout de suite
  function ecoute(){
    pages().forEach(function(p){
      if(p.doc.__passeEcoute) return; p.doc.__passeEcoute=true;
      try{ p.doc.addEventListener('play',function(e){ if(e.target&&e.target.tagName==='VIDEO') setTimeout(tour,30); },true); }catch(e){}
    });
  }
  setInterval(tour,250);
  setInterval(ecoute,700);
  ecoute(); tour();
  }
})();
