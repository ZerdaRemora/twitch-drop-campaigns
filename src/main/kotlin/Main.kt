import com.github.kittinunf.fuel.core.Headers
import com.github.kittinunf.fuel.gson.responseObject
import com.github.kittinunf.fuel.httpPost
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.cdimascio.dotenv.dotenv
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.`java-time`.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.util.*

data class Game(val id: Int, val displayName: String)
data class DropCampaign(val id: String, val name: String, val game: Game, val status: String, val startAt: Date, val endAt: Date)
data class CurrentUser(val login: String, val dropCampaigns: List<DropCampaign>)
data class ResponseData(val currentUser: CurrentUser)
data class ApiResponse(val data: ResponseData)

object Games: Table() {
    val id = integer("id").uniqueIndex()
    val name = varchar("name", 255)

    override val primaryKey = PrimaryKey(id, name = "PK_Games_ID")
}

object Drops: Table() {
    val id = varchar("id", 40)
    val name = varchar("name", 255)
    val game = reference("game_id", Games.id)
    val started = datetime("started")
    val ended = datetime("ended")
    val status = varchar("status", 20)

    override val primaryKey = PrimaryKey(id, name = "PK_Drops_ID")
}


fun main() {
    val logger = LoggerFactory.getLogger("twitch-drop-campaigns")
    val dotenv = dotenv {
        ignoreIfMissing = true
    }

    val pgHost = dotenv["POSTGRES_HOST"] ?: "postgres"
    val pgPort = dotenv["POSTGRES_PORT"] ?: "5432"
    val pgPassword = dotenv["POSTGRES_PASSWORD"] ?: "postgres"
    val oauth = dotenv["OAUTH_TOKEN"]
    val clientId = dotenv["CLIENT_ID"]

    val requestBody = "[{\"operationName\":\"ViewerDropsDashboard\",\"variables\":{},\"extensions\":{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"c4d61d7b71d03b324914d3cf8ca0bc23fe25dacf54120cc954321b9704a3f4e2\"}}}]"

    val (_, _, result) = "https://gql.twitch.tv/gql".httpPost()
        .body(requestBody)
        .header(Headers.AUTHORIZATION, oauth) // TODO: This can and probably will change.
        .header("Client-ID", clientId)
        .responseObject<List<ApiResponse>>()

    Database.connect(buildConnectionPool(pgHost, pgPort, pgPassword))

    transaction {
        SchemaUtils.createMissingTablesAndColumns(Drops)

        val response = result.get()[0]
        response.data.currentUser.dropCampaigns.forEach {
            Games.insertIgnore { col ->
                col[id] = it.game.id
                col[name] = it.game.displayName
            }

            val campaignCount = Drops.select { Drops.id eq it.id }.count()
            if (campaignCount == 0L) {
                alertNewCampaign(it, logger)
            } else {
                // Update status if changed.
                Drops.select { Drops.id.eq(it.id) and Drops.status.neq(it.status) }.forEach { drop ->
                    logger.info("[Update] ${drop[Drops.name]} (${drop[Drops.started]}) status changed to ${it.status}")

                    Drops.update({ Drops.id eq it.id}) { update ->
                        update[status] = it.status
                    }
                }

            }

            Drops.insertIgnore { col ->
                col[id] = it.id
                col[name] = it.name
                col[game] = it.game.id
                col[started] = Instant.ofEpochMilli(it.startAt.time).atOffset(ZoneOffset.UTC).toLocalDateTime()
                col[ended] = Instant.ofEpochMilli(it.endAt.time).atOffset(ZoneOffset.UTC).toLocalDateTime()
                col[status] = it.status
            }
        }
    }
}

fun buildConnectionPool(pgHost: String, pgPort: String, pgPassword: String): HikariDataSource {
    val config = HikariConfig()
    config.jdbcUrl = "jdbc:postgresql://${pgHost}:${pgPort}/twitchdrops"
    config.username = "postgres"
    config.password = pgPassword

    return HikariDataSource(config)
}

fun alertNewCampaign(campaign: DropCampaign, logger: Logger) {
    logger.info("[New] ${campaign.name} for ${campaign.game.displayName}")
    logger.info("[New] Currently ${campaign.status}")
    logger.info("[New] From ${campaign.startAt} to ${campaign.endAt}")
}