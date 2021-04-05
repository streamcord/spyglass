package io.streamcord.spyglass

import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import org.bson.Document

class DatabaseController private constructor(
    val subscriptions: MongoCollection<Document>,
    val notifications: MongoCollection<Document>,
) {
    companion object {
        fun create(config: AppConfig.Mongo): DatabaseController {
            val client = try {
                MongoClients.create("mongodb://${config.connection}")
            } catch (ex: IllegalArgumentException) {
                logger.fatal(
                    ExitCodes.INVALID_DB_CONNECTION_STRING,
                    "Invalid MongoDB connection string ${config.connection}",
                    ex
                )
            }

            val database = try {
                client.getDatabase(config.database)
            } catch (ex: IllegalArgumentException) {
                logger.fatal(ExitCodes.INVALID_DB_NAME, "Invalid MongoDB database name ${config.database}", ex)
            }

            val subscriptions = database.tryGetCollection(config.collections.subscriptions)
            val notifications = database.tryGetCollection(config.collections.notifications)

            return DatabaseController(subscriptions, notifications)
        }

        private fun MongoDatabase.tryGetCollection(collectionName: String) = try {
            getCollection(collectionName)
        } catch (ex: IllegalArgumentException) {
            logger.fatal(ExitCodes.INVALID_DB_COLLECTION_NAME, "Invalid MongoDB collection name $collectionName", ex)
        }
    }
}
