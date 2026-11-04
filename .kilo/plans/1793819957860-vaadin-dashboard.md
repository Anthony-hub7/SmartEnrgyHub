# Plan : Interface Vaadin — tableau de bord SmartEnergy Hub

## Contexte

Tous les services Spring Boot existent (collector 8081, storage 8082, analytics 8083, sensor 8084). Il manque une interface web simple pour afficher l'état du système : capteurs, mesures, analytics (résumé, anomalies, dépassements, prévision).

Le projet est un multi-module Maven (Spring Boot 3.5.0, Java 17). Pas de gateway-service codé (que des exemples `.example`). Chaque service expose ses propres endpoints REST sur son port.

## Décisions d'architecture

1. **Nouveau module Maven** : `platform/vaadin-dashboard` (ou `dashboard-service`), empaqueté en `jar`, dépendant de `platform-web` + les contracts (`common-contracts`, `measurement-contracts`, `analytics-contracts`, `sensor-contracts`).
2. **Vaadin 24 LTS** (compatible Spring Boot 3.x / Java 17) : `com.vaadin:vaadin-spring-boot-starter`. C'est le plus simple : un seul projet Spring Boot autonome, pas besoin de modifier les services existants.
3. **Accès aux données** : via `RestClient` (déjà utilisé dans le codebase, ex. `SensorGateway`) vers chaque service sur son port localhost, pas de client Feign/OpenAPI supplémentaire.
4. **Port dashboard** : 8085 (nouveau, non utilisé).
5. **Vue unique** (`MainView`) : onglets ou sections verticales :
   - Santé (actuator /actuator/health des 4 services)
   - Capteurs (liste depuis sensor-service GET /api/sensors)
   - Dernière mesure par capteur (storage GET /api/measurements/{id}/latest)
   - Historique (storage GET /api/measurements/{id})
   - Résumé / quotidien / anomalies / dépassements (analytics)
   - Prévision (501 si IA off — affiché en lecture seule)
6. **Aucune écriture** : lecture seule, pour éviter de complexifier.
7. **Gestion d'erreur** : si un service est down, afficher un encart "indisponible" au lieu de faire planter la page.
8. **Enregistrement du module** : ajouter `<module>platform/vaadin-dashboard</module>` au pom parent.

## Données affichées (mapping endpoints → UI)

| Section | Service | Endpoint | Contrat |
|---|---|---|---|
| Santé | chaque service | GET /actuator/health | — |
| Capteurs | sensor-service (8084) | GET /api/sensors | PageResponse<SensorResponse> |
| Dernière mesure | storage (8082) | GET /api/measurements/{id}/latest | MeasurementItem |
| Historique | storage (8082) | GET /api/measurements/{id} | MeasurementHistoryResponse |
| Résumé | analytics (8083) | GET /api/analytics/{id}/summary | SummaryResponse |
| Quotidien | analytics (8083) | GET /api/analytics/{id}/daily-summary | DailySummaryResponse |
| Anomalies | analytics (8083) | GET /api/analytics/{id}/anomalies | PageResponse<AnomalyResponse> |
| Dépassements | analytics (8083) | GET /api/analytics/exceedances?threshold= | List<ExceedanceResponse> |
| Prévision | analytics (8083) | GET /api/analytics/{id}/forecast | ForecastResponse (501 si off) |

## Fichiers à créer

- `platform/vaadin-dashboard/pom.xml`
- `platform/vaadin-dashboard/src/main/java/com/smartenergy/dashboard/DashboardApplication.java`
- `platform/vaadin-dashboard/src/main/java/com/smartenergy/dashboard/config/ServiceClients.java` (RestClient beans par service)
- `platform/vaadin-dashboard/src/main/java/com/smartenergy/dashboard/view/MainView.java` (Vue principale Vaadin)
- `platform/vaadin-dashboard/src/main/java/com/smartenergy/dashboard/view/HealthView.java`
- `platform/vaadin-dashboard/src/main/java/com/smartenergy/dashboard/view/SensorsView.java`
- `platform/vaadin-dashboard/src/main/java/com/smartenergy/dashboard/view/MeasurementsView.java`
- `platform/vaadin-dashboard/src/main/java/com/smartenergy/dashboard/view/AnalyticsView.java`
- `platform/vaadin-dashboard/src/main/resources/application.yml`
- `platform/vaadin-dashboard/src/main/resources/META-INF/spring.factories` (optionnel, Spring Boot auto-config)

## Tâches d'implementation

1. Créer `platform/vaadin-dashboard/pom.xml` avec dépendances Vaadin + Spring Web + RestClient.
2. Ajouter le module au `pom.xml` parent.
3. Créer `DashboardApplication.java` avec `@SpringBootApplication`.
4. Créer `ServiceClients.java` : 4 beans `RestClient` (collector, storage, analytics, sensor) avec baseUrl depuis `application.yml`.
5. Créer les 4 vues Vaadin (Horizontal/VerticalLayout, Grid, Text, Label, Button de rafraîchissement).
6. Créer `MainView.java` avec `Tabs` ou `Accordion` regroupant les vues.
7. Configurer `application.yml` : port 8085, URLs des services, Vaadin en mode production.
8. Vérifier la compilation (`./mvnw -pl platform/vaadin-dashboard -am compile`).
9. Lancer le dashboard et vérifier l'affichage contre les 4 services UP.

## Risques

- Vaadin nécessite des ressources frontend (npm) au build : vérifier que `vaadin-spring-boot-starter` gère le frontend automatiquement (oui, via plugin Vaadin Maven).
- CORS : les appels RestClient sont server-side, pas de problème CORS.
- Si un service est down, RestClient lève une exception : encapsuler dans `try/catch` et afficher un message d'erreur dans l'UI.
- Pas de gateway : l'UI appelle directement chaque service sur son port.

## Validation

- `mvn -pl platform/vaadin-dashboard -am compile` réussit.
- Lancement : `java -jar platform/vaadin-dashboard/target/app.jar` sur le port 8085.
- Affichage des capteurs, dernières mesures, résumés analytics sans erreur 500.

## Ouvert / questions

- Aucun : plan prêt à implémenter.