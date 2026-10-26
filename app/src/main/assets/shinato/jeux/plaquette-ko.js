/* La Plaquette Dorée : la vidéo qui passe quand chaque ennemie met Rudy K.O.
   leaf = la femme aux lianes, cave = la sauvage à la lance, whip = la femme au fouet,
   blonde = la blonde, gum = la femme chewing-gum, boar = la femme sanglier, boss = la boss */
window.PLAQUETTE_KO_VIDEOS={
  gum:  '/assets/shinato/m/plaquette_ko_gum.mp4',
  leaf: '/assets/shinato/m/plaquette_ko_lianes.mp4',
  whip: '/assets/shinato/m/plaquette_ko_fouet.mp4',
  cave: '/assets/shinato/m/plaquette_ko_lance.mp4',
  boar: '/assets/shinato/m/plaquette_ko_sanglier.mp4',
  blonde: '/assets/shinato/m/plaquette_ko_blonde.mp4',
  boss: '/assets/shinato/m/plaquette_ko_boss.mp4'
};

/* la vidéo quand Rudy arrive à la map 4 (la salle du temple), juste avant le combat contre la boss */
window.PLAQUETTE_BOSS_INTRO='/assets/shinato/m/plaquette_boss_intro.mp4';

/* la vidéo quand Rudy bat la boss et gagne ; ensuite il apparaît devant l'hôpital de Vaviel */
window.PLAQUETTE_VICTOIRE='/assets/shinato/m/plaquette_victoire.mp4';

/* Le jeu démarre tout seul (après Rudy Jet, ou « Retourner sur l'île ») : on ne montre jamais le menu.
   Écran noir pendant le chargement, puis la partie apparaît directement. */
(function(){
  var t=document.getElementById('title'); if(t) t.classList.add('hide');
  var c=document.createElement('div');
  c.style.cssText='position:fixed;inset:0;z-index:99999;background:#000;pointer-events:none;transition:opacity .35s';
  (document.body||document.documentElement).appendChild(c);
  var n=0, iv=setInterval(function(){
    var pret=false; try{ pret=(typeof state!=='undefined'&&state!=='title'); }catch(e){}
    if(pret||++n>300){ clearInterval(iv); c.style.opacity='0'; setTimeout(function(){ c.remove(); },400); }
  },50);
})();
