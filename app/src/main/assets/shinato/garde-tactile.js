/* garde-tactile.js — à charger en tout premier dans un jeu ou un lieu.
   Sur le téléphone, la page « perd le focus » (événement blur) dès que l'appli passe la main
   d'un cadre à l'autre (lieu ↔ Shinato, vidéo, panneau…), même si le doigt est toujours posé.
   Beaucoup de jeux relâchaient alors le joystick et les boutons : il fallait retoucher l'écran,
   parfois avec deux doigts. Ici, ce « blur » n'est plus pris en compte tant que l'écran est visible ;
   quand l'appli passe vraiment en arrière-plan (écran éteint, autre appli), tout est relâché comme avant. */
(function(){
 if(window.__gardeTactile) return; window.__gardeTactile=true;
 var add=window.addEventListener, rem=window.removeEventListener, enveloppes=new WeakMap();
 function enveloppe(f){
  if(typeof f!=='function') return f;
  var g=enveloppes.get(f);
  if(!g){ g=function(e){ if(document.hidden) return f.call(this,e); }; enveloppes.set(f,g); }
  return g;
 }
 window.addEventListener=function(type,f,o){ return add.call(this,type,(type==='blur'&&this===window)?enveloppe(f):f,o); };
 window.removeEventListener=function(type,f,o){ return rem.call(this,type,(type==='blur'&&this===window&&typeof f==='function'&&enveloppes.has(f))?enveloppes.get(f):f,o); };
 // l'appli passe en arrière-plan : on envoie le « blur » d'origine pour que le jeu relâche tout
 document.addEventListener('visibilitychange',function(){ if(document.hidden){ try{ window.dispatchEvent(new Event('blur')); }catch(e){} } });
})();
