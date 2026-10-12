/* ---------- ⏩ Passer la vidéo : un petit bouton en haut à droite de TOUTES les vidéos du jeu ----------
   Chargé une seule fois par les pages principales (Shinato, la chambre, le téléphone, les visites…).
   Il s'installe tout seul dans chaque page du jeu ouverte à l'intérieur (lieux, mini-jeux, missions),
   et dès qu'une vidéo est jouée en grand, le bouton apparaît ; un appui = la vidéo va directement à la fin
   (exactement comme si on l'avait regardée jusqu'au bout : la suite du jeu se déroule normalement). */
(function(){
  if(window.__passeurPrincipal) return; window.__passeurPrincipal=true;

  function installe(win){
    var doc; try{ doc=win.document; if(!doc||!doc.documentElement) return; }catch(e){ return; }   // page d'un autre site : on n'y touche pas
    if(win.__passeurVideo) return; win.__passeurVideo=true;
    var bt=null, cible=null, passee=null;
    function bouton(){
      if(bt&&doc.body&&doc.body.contains(bt)) return bt;
      if(!doc.body) return null;
      bt=doc.createElement('div'); bt.setAttribute('aria-label','Passer la vidéo');
      bt.innerHTML='<svg viewBox="0 0 24 24" width="22" height="22"><path d="M3 5l9 7-9 7z M12 5l9 7-9 7z" fill="#fff"/></svg>';
      bt.style.cssText='position:fixed;z-index:2147483647;top:calc(10px + env(safe-area-inset-top,0px));right:10px;width:46px;height:46px;'+
        'display:none;align-items:center;justify-content:center;border-radius:50%;background:rgba(0,0,0,.55);'+
        'border:2px solid rgba(255,255,255,.75);box-shadow:0 2px 10px rgba(0,0,0,.6);cursor:pointer;touch-action:manipulation;-webkit-tap-highlight-color:transparent';
      var t0=0;
      var go=function(e){ if(e){ e.preventDefault(); e.stopPropagation(); } var n=Date.now(); if(n-t0<500) return; t0=n; passe(); };
      bt.addEventListener('touchstart',go,{passive:false}); bt.addEventListener('pointerdown',function(e){ e.stopPropagation(); });
      bt.addEventListener('click',go);
      doc.body.appendChild(bt); return bt;
    }
    function grande(v){
      try{
        if(v.loop||v.paused||v.ended) return false;
        var r=v.getBoundingClientRect(), W=win.innerWidth||1, H=win.innerHeight||1;
        if(r.width<2||r.height<2) return false;
        var w=Math.min(r.right,W)-Math.max(r.left,0), h=Math.min(r.bottom,H)-Math.max(r.top,0);
        if(w<=0||h<=0) return false;
        var st=win.getComputedStyle(v); if(st.visibility==='hidden'||+st.opacity===0) return false;
        return (w*h)>=0.30*W*H;                       // une vidéo jouée en grand (pas un petit décor animé)
      }catch(e){ return false; }
    }
    function passe(){
      var v=cible; if(!v) return;
      passee=v; var src=v.currentSrc||v.src;
      try{
        if(isFinite(v.duration)&&v.duration>0){ v.muted=true; v.currentTime=Math.max(0,v.duration-0.05); var q=v.play(); if(q&&q.catch) q.catch(function(){}); }
      }catch(e){}
      // au cas où la vidéo ne finirait pas toute seule : on annonce la fin nous-mêmes
      setTimeout(function(){ try{ if(!v.ended&&(v.currentSrc||v.src)===src){ v.pause(); v.dispatchEvent(new Event('ended')); } }catch(e){} },900);
      setTimeout(function(){ try{ if(v.muted&&(v.currentSrc||v.src)!==src) v.muted=false; }catch(e){} },1500);
      if(bt) bt.style.display='none';
    }
    // les anciens boutons « Passer », « ✕ Fermer »… posés sur la vidéo : cachés tant que notre bouton est là
    var caches=[];
    var RE=/^\s*(passer|skip|fermer|[✕×x]\s*fermer|[✕×x])\s*\.{0,3}\s*$/i;
    function cacheAnciens(){
      var els; try{ els=doc.querySelectorAll('button,div,span,a'); }catch(e){ return; }
      for(var i=0;i<els.length;i++){
        var el=els[i]; if(el===bt||(bt&&bt.contains(el))||el.__passeCache) continue;
        var t=(el.textContent||'').trim(); if(!t||t.length>14||!RE.test(t.replace(/[▶►⏩⏭]/g,'').trim())) continue;
        if(el.children.length>2) continue;
        var r=el.getBoundingClientRect(); if(r.width<4||r.height<4||r.width>280||r.height>100) continue;
        el.__passeCache=el.style.display; el.style.display='none'; caches.push(el);
      }
    }
    function remetAnciens(){ caches.forEach(function(el){ try{ el.style.display=el.__passeCache||''; delete el.__passeCache; }catch(e){} }); caches=[]; }
    function tour(){
      var vs; try{ vs=doc.getElementsByTagName('video'); }catch(e){ return; }
      cible=null;
      for(var i=0;i<vs.length;i++){ if(grande(vs[i])){ cible=vs[i]; break; } }
      if(passee&&cible===passee&&(cible.currentSrc||cible.src)&&cible.currentTime>=cible.duration-0.3) cible=null;   // déjà passée
      if(passee&&cible!==passee) { try{ if(passee.muted&&!passee.ended) passee.muted=false; }catch(e){} passee=null; }
      var b=bouton(); if(!b) return;
      if(cible){
        cacheAnciens();
        b.style.display='flex';
        // s'il y a déjà un bouton (✕ Fermer…) dans le coin, le nôtre se met juste en dessous
        b.style.top='calc(10px + env(safe-area-inset-top,0px))'; b.style.pointerEvents='none';
        var occupe=false, W=win.innerWidth||0;
        [[W-14,14],[W-33,14],[W-52,14],[W-14,33],[W-33,33],[W-52,33],[W-14,52],[W-33,52],[W-52,52]].forEach(function(pt){
          var el=null; try{ el=doc.elementFromPoint(pt[0],pt[1]); }catch(e){}
          if(el&&el!==b&&!b.contains(el)&&el!==cible&&!el.contains(cible)) occupe=true; });
        b.style.pointerEvents='auto';
        if(occupe) b.style.top='calc(66px + env(safe-area-inset-top,0px))';
      } else { b.style.display='none'; if(caches.length) remetAnciens(); }
    }
    win.setInterval(tour,250);
  }

  // chaque page du jeu (même ouverte dans un cadre, une mission dans un lieu…) reçoit le bouton
  function parcourt(win,prof){
    installe(win);
    if(prof>6) return;
    var n=0; try{ n=win.frames.length; }catch(e){ return; }
    for(var i=0;i<n;i++){ try{ parcourt(win.frames[i],prof+1); }catch(e){} }
  }
  setInterval(function(){ parcourt(window,0); },700);
  parcourt(window,0);
})();
