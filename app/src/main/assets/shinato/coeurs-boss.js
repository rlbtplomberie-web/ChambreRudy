/* coeurs-boss.js — pendant le combat contre le boss final : toutes les 10 secondes, un cœur tombe du ciel
   près de Rudy. Il le ramasse en marchant dessus : toute sa vie revient. Pas ramassé : il clignote puis
   disparaît au bout de 6 secondes (jamais plusieurs à la fois).
   Chaque jeu le branche avec :
     const CB = CoeurBoss({ actif:()=>bool, rudy:()=>({x,y,libre}), soigne:()=>{}, zone:()=>({x0,x1,y0,y1}), rx, ry });
     CB.maj(dt)                 dans sa boucle de jeu (dt en secondes)
     CB.dessine(ctx, ecran)     dans son dessin du monde ; ecran(x,y) -> [xÉcran, yÉcran, échelle]
   croix:true : contour doré et croix blanche (quand l'ennemie lance elle-même des cœurs)          */
(function(){
  var AC=null;
  function son(){
    try{
      if(!AC) AC=new (window.AudioContext||window.webkitAudioContext)();
      if(AC.state==='suspended') AC.resume();
      var t=AC.currentTime;
      [660,880,1320,1760].forEach(function(f,i){
        var o=AC.createOscillator(), g=AC.createGain(); o.type='triangle'; o.frequency.value=f;
        g.gain.setValueAtTime(0.0001,t+i*0.07); g.gain.exponentialRampToValueAtTime(0.22,t+i*0.07+0.02);
        g.gain.exponentialRampToValueAtTime(0.0001,t+i*0.07+0.32);
        o.connect(g); g.connect(AC.destination); o.start(t+i*0.07); o.stop(t+i*0.07+0.35);
      });
    }catch(e){}
  }
  function coeur(ctx,x,y,s){        // un cœur centré en (x,y), largeur ~ 2s
    ctx.beginPath();
    ctx.moveTo(x, y+s*0.9);
    ctx.bezierCurveTo(x-s*1.25, y+s*0.05, x-s*1.05, y-s*1.05, x, y-s*0.4);
    ctx.bezierCurveTo(x+s*1.05, y-s*1.05, x+s*1.25, y+s*0.05, x, y+s*0.9);
    ctx.closePath();
  }
  window.CoeurBoss=function(o){
    var C={liste:[], t:0, fx:[]}, rx=o.rx||42, ry=o.ry||26, PERIODE=o.periode||10, VIE=o.vie||6;
    function nouveau(){
      var r=o.rudy(), z=o.zone(); if(!r) return;
      var d=(Math.random()<0.5?-1:1)*(120+Math.random()*170), x=r.x+d;
      if(x<z.x0+30||x>z.x1-30) x=r.x-d;
      x=Math.max(z.x0+30,Math.min(z.x1-30,x));
      var y=Math.max(z.y0,Math.min(z.y1,r.y+(Math.random()-0.5)*(z.y1-z.y0)*0.6));
      C.liste.push({x:x,y:y,h:520,vh:0,sol:false,age:0});
    }
    C.maj=function(dt){
      if(!(dt>0)) return; dt=Math.min(dt,0.1);
      for(var i=C.fx.length-1;i>=0;i--){ C.fx[i].t+=dt; if(C.fx[i].t>0.7) C.fx.splice(i,1); }
      if(!o.actif()){ C.t=0; C.liste.length=0; return; }
      C.t+=dt;
      if(C.t>=PERIODE){ C.t=0; if(!C.liste.length) nouveau(); }
      var r=o.rudy();
      for(var j=C.liste.length-1;j>=0;j--){
        var c=C.liste[j];
        if(!c.sol){ c.vh+=900*dt; c.h-=c.vh*dt; if(c.h<=0){ c.h=0; c.sol=true; } }
        else c.age+=dt;
        if(c.age>VIE){ C.liste.splice(j,1); continue; }
        if(r&&r.libre&&c.h<60&&Math.abs(r.x-c.x)<rx&&Math.abs(r.y-c.y)<ry){
          C.liste.splice(j,1); try{ o.soigne(); }catch(e){} son();
          C.fx.push({x:c.x,y:c.y,t:0});
        }
      }
    };
    C.dessine=function(ctx,ecran){
      var now=performance.now()/1000;
      C.liste.forEach(function(c){
        var p=ecran(c.x,c.y), k=p[2]||1, X=p[0], Y=p[1];
        if(c.age>VIE-1.8 && Math.floor(now*10)%2) return;           // il clignote avant de disparaître
        var s=20*k, flot=c.sol?Math.sin(now*4+c.x)*3*k:0, cy=Y-c.h*k-26*k+flot;
        ctx.save();
        ctx.globalAlpha=0.35; ctx.fillStyle='#000'; ctx.beginPath();
        ctx.ellipse(X,Y,16*k*(c.sol?1:Math.max(0.3,1-c.h/520)),5*k,0,0,Math.PI*2); ctx.fill();
        ctx.globalAlpha=1;
        ctx.shadowColor='rgba(255,60,90,0.95)'; ctx.shadowBlur=18*k;
        var g=ctx.createLinearGradient(X,cy-s,X,cy+s); g.addColorStop(0,'#ff8aa0'); g.addColorStop(0.5,'#ff1f45'); g.addColorStop(1,'#b0001e');
        ctx.fillStyle=g; coeur(ctx,X,cy,s); ctx.fill();
        ctx.shadowBlur=0; ctx.lineWidth=(o.croix?3:2)*k; ctx.strokeStyle=o.croix?'#ffd65a':'rgba(255,255,255,0.85)'; ctx.stroke();
        if(o.croix){ ctx.fillStyle='#fff'; ctx.fillRect(X-s*0.12,cy-s*0.45,s*0.24,s*0.75); ctx.fillRect(X-s*0.37,cy-s*0.2,s*0.74,s*0.24); }   // cœur de vie (pas celui d'une ennemie)
        ctx.fillStyle='rgba(255,255,255,0.75)'; ctx.beginPath(); ctx.ellipse(X-s*0.42,cy-s*0.38,s*0.22,s*0.13,-0.6,0,Math.PI*2); ctx.fill();
        ctx.restore();
      });
      C.fx.forEach(function(f){
        var p=ecran(f.x,f.y), k=p[2]||1, a=1-f.t/0.7, rr=(20+f.t*90)*k;
        ctx.save(); ctx.globalAlpha=a; ctx.strokeStyle='#ff6f8a'; ctx.lineWidth=4*k;
        ctx.beginPath(); ctx.arc(p[0],p[1]-60*k,rr,0,Math.PI*2); ctx.stroke();
        ctx.fillStyle='#ffffff'; ctx.font='bold '+Math.round(22*k)+'px Impact, Arial Black, sans-serif'; ctx.textAlign='center';
        ctx.fillText('VIE MAX !',p[0],p[1]-110*k-f.t*40*k); ctx.restore();
      });
    };
    return C;
  };
})();
