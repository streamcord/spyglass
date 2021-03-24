package io.streamcord.spyglass

import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import org.bson.Document
import kotlin.system.exitProcess

class DatabaseController private constructor(
    val subscriptions: MongoCollection<Document>,
    val notifications: MongoCollection<Document>,
) {
    companion object {
        fun create(config: AppConfig.Mongo): DatabaseController {
            val client = try {
                MongoClients.create(config.connection)
            } catch (ex: IllegalArgumentException) {
                logger.error("Invalid MongoDB connection string ${config.connection}", ex)
                exitProcess(ExitCodes.INVALID_DB_CONNECTION_STRING)
            }

            val database = try {
                client.getDatabase(config.database)
            } catch (ex: IllegalArgumentException) {
                logger.error("Invalid MongoDB database name ${config.database}", ex)
                exitProcess(ExitCodes.INVALID_DB_NAME)
            }

            val subscriptions = database.tryGetCollection(config.collections.subscriptions)
            val notifications = database.tryGetCollection(config.collections.notifications)

            return DatabaseController(subscriptions, notifications)
        }

        private fun MongoDatabase.tryGetCollection(collectionName: String) = try {
            getCollection(collectionName)
        } catch (ex: IllegalArgumentException) {
            logger.error("Invalid MongoDB collection name $collectionName", ex)
            exitProcess(ExitCodes.INVALID_DB_COLLECTION_NAME)
        }
    }
}
