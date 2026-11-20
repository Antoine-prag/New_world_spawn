# New World Spawn

Mod NeoForge 1.21.1 — dimension d'accueil du serveur.

## Contenu

- **Dimension `newworldspawn:spawn`** : monde aquatique (ocean gele figé), cercle de rayon **20 chunks** (bordure au demarrage, centre 0,0).
- **Aventure forcé** dans la dimension (jamais survival), gamemode restauré à la sortie (ops exemptés par config).
- **Pas de PvP** dans la dimension.
- **Eau quasi non-nageable** dans la dimension (joueur très lourd, coule doucement).
- **/spawn** : téléportation dans la dimension d'accueil — délai 5 s, annulé si interaction (config `spawnDelaySeconds`).
- **/setspawn all|off** *(OP)* : tous les joueurs qui se connectent arrivent dans la dimension d'accueil. **Ne touche pas au respawn par lit** : uniquement le point d'arrivée à la connexion.
- **/randomtp [joueur]** : sortie vers un point aléatoire de l'overworld. `/randomtp <joueur>` OP uniquement.

## Logs

Fichier dédié `logs/newworldspawn.log` (rotation 5 Mo × 3) — pas la console serveur.

## Config

`newworldspawn_server.toml` : délai /spawn, radio bordure, aventure forcée, exemptions ops, talents d'eau, portée /randomtp.
