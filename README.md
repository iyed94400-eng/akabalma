# Susanoo 2.1 (Fabric 1.20.1)

## Compiler
Java 17 + connexion internet, puis dans ce dossier :
    ./gradlew build        (Windows : gradlew.bat build)
Le mod est généré dans build/libs/susanoo-2.1.0.jar (à mettre dans .minecraft/mods avec Fabric API 0.92.x).

## Touches (modifiables dans Options > Contrôles > Susanoo)
- G : invoquer / renvoyer le Susanoo (il faut un Mangekyō dans l'inventaire ; clic droit sur l'objet fait pareil)
- H : forme suivante (Maj+H : précédente) : Côtes -> Squelette -> Armure -> Parfaite (géante)
- R : attaque (maintenir pour enchaîner)
- V : technique spéciale (flèche / onde de chakra)
- C : piétinement (forme Parfaite seulement) - cratère + onde de choc qui projette tout autour
- B : coup de pied (forme Parfaite seulement) - balayage devant le géant, projette les ennemis loin et haut

## Couleurs
Madara = bleu (objet `mangekyo`, recette d'origine), Itachi = rouge, Sasuke = violet, Shisui = vert, Obito = orange.
Craft : Mangekyō quelconque + teinture (bleue, rouge, violette, verte, orange).

## Physique
Le Susanoo enveloppe le joueur et le suit sans retard. Il absorbe les dégâts (bouclier : 20/40/80/160 PV selon la forme,
se régénère hors combat), annule chute et feu, repousse moins (anti-recul formes 2-4) et consomme de la faim.
Les formes Armure/Parfaite brisent les blocs tendres (si mobGriefing est actif).
Réglages : tableaux de constantes en haut de SusanooManager.java et SusanooEntity.java.

## Nouveautés 2.1
- **Jambes (forme Parfaite)** : le Susanoo se tient debout sur de vraies jambes. Le joueur flotte (vol accordé tant que la forme
  Parfaite est active, restitué ensuite + chute lente) à environ 11 blocs du sol ; les pieds se posent sur le terrain (cinématique inverse),
  les genoux plient selon le relief, les jambes ont de l'inertie (ressorts amortis, elles traînent derrière le mouvement).
  Attaques : piétinement (C), coup de pied (B), et pas écrasant en marchant (blesse ce qui est sous le pied, écrase la végétation).
- **FOV** : le champ de vision grandit à chaque forme (+4 / +10 / +17 / +28), avec un petit coup de pouce à chaque transformation,
  piétinement et technique spéciale. Réglage dans SusanooClient.FOV_BONUS (nécessite le mixin GameRendererMixin).
- **Aura** : flammes sombres à cœur incandescent qui s'élèvent du corps, coques de fumée qui ondulent, anneau de runes et de flammes
  au sol, fumée noire / âmes / étincelles, foudre décorative à chaque montée de forme, battement de cœur sourd (Armure / Parfait),
  bords d'écran assombris qui pulsent. Les créatures proches sont ralenties et affaiblies (rayon 4 / 7 / 12 / 24 selon la forme).
  Réglages : SusanooEntity.AURA_PARTICLES (moins de particules), SusanooManager.AURA_DEBUFF / AURA_SOUNDS, SusanooClient.VIGNETTE.
