package com.belijolie.scanner.data

import java.util.UUID

/**
 * Identifiants et horodatage des lignes synchronisées.
 *
 * Toutes les tables synchronisées avec le serveur (catégories, produits,
 * appareils, canaux, lignes de panier, paramètres partagés) partagent 3
 * colonnes techniques :
 *  - `updatedAt` : ms epoch de la dernière modification locale. Le serveur
 *    s'en sert pour départager deux appareils qui modifient la même ligne.
 *  - `deleted`   : suppression « douce ». On garde la ligne pour pouvoir
 *    propager la suppression aux autres appareils. Toutes les lectures
 *    filtrent `deleted = 0`.
 *  - `dirty`     : modifiée localement, pas encore envoyée au serveur.
 */
fun newId(): String = UUID.randomUUID().toString()

fun nowMs(): Long = System.currentTimeMillis()
