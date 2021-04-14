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
import kotlin.collections.HashMap

// Game data class
data class Game(val id: Int, val displayName: String, val boxArtURL: String)

// Drop Campaign data classes
data class DropCampaign(val id: String, val name: String, val game: Game, val status: String, val startAt: Date, val endAt: Date)
data class CurrentUser(val login: String, val dropCampaigns: List<DropCampaign>)
data class ResponseData(val currentUser: CurrentUser)
data class ApiResponse(val data: ResponseData)

// Campaign Details data classes
data class Benefit(val id: String, val name: String, val imageAssetURL: String)
data class BenefitEdges(val benefit: Benefit, val entitlementLimit: Int)
data class TimeBasedDrops(val id: String, val name: String, val requiredMinutesWatched: Int, val preconditionDrops: List<TimeBasedDrops>?, val benefitEdges: List<BenefitEdges>)
data class DropCampaignDetails(val id: String, val timeBasedDrops: List<TimeBasedDrops>)
data class DetailsUser(val dropCampaign: DropCampaignDetails)
data class DetailsResponseData(val user: DetailsUser)
data class DetailsApiResponse(val data: DetailsResponseData)

object Games: Table() {
    val id = integer("id").uniqueIndex()
    val name = varchar("name", 255)
    val boxArtUrl = varchar("box_art_url", 255).nullable()

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

object DropBenefits: Table() {
    val id = varchar("id", 255).uniqueIndex()
    val name = varchar("name", 255)
    val dropId = reference("drop_id", Drops.id)
    val requiredMinutesWatched = integer("required_minutes_watched")
    val entitlementLimit = integer("entitlement_limit")
    val benefitArtUrl = varchar("benefit_art_url", 255).nullable()

    override val primaryKey = PrimaryKey(id, name = "PK_DropBenefits_ID")
}

object DropBenefitPreReqs: Table() {
    val benefitId = reference("benefit_id", DropBenefits.id)
    val preReqBenefitId = reference("pre_req_benefit_id", DropBenefits.id)

    override val primaryKey = PrimaryKey(benefitId, preReqBenefitId, name = "PK_DropBenefitPreReqs_ID")
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
    val channelLogin = dotenv["CHANNEL_LOGIN"]

    val requestBody = "[{\"operationName\":\"ViewerDropsDashboard\",\"variables\":{},\"extensions\":{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"c4d61d7b71d03b324914d3cf8ca0bc23fe25dacf54120cc954321b9704a3f4e2\"}}}]"

    val (_, _, result) = "https://gql.twitch.tv/gql".httpPost()
        .body(requestBody)
        .header(Headers.AUTHORIZATION, oauth) // TODO: This can and probably will change.
        .header("Client-ID", clientId)
        .responseObject<List<ApiResponse>>()
    Database.connect(buildConnectionPool(pgHost, pgPort, pgPassword))

    val allDropIds = mutableListOf<String>()

    transaction {
        SchemaUtils.createMissingTablesAndColumns(Games)
        SchemaUtils.createMissingTablesAndColumns(Drops)

        val response = result.get()[0]
        response.data.currentUser.dropCampaigns.forEach {
            Games.insertIgnore { col ->
                col[id] = it.game.id
                col[name] = it.game.displayName
                col[boxArtUrl] = it.game.boxArtURL
            }

            /*
            // Fix box art urls:
            Games.update({ Games.id eq it.game.id}) { update ->
                update[boxArtUrl] = it.game.boxArtURL
            }
            */

            if (it.status != "EXPIRED")
                allDropIds.add(it.id)

            val campaignCount = Drops.select { Drops.id eq it.id }.count()
            if (campaignCount == 0L) {
                alertNewCampaign(it, logger)
            } else {
                // Update status if changed.
                Drops.select { Drops.id.eq(it.id) and Drops.status.neq(it.status) }.forEach { drop ->
                    logger.info("[Update] ${drop[Drops.name]} (${drop[Drops.started]}) status changed to ${it.status}")

                    Drops.update({ Drops.id eq it.id }) { update ->
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

    transaction {
        SchemaUtils.createMissingTablesAndColumns(DropBenefits)
        SchemaUtils.createMissingTablesAndColumns(DropBenefitPreReqs)

        val chunkedDropsList = allDropIds.chunked(35) // API has a limit of 35 campaigns per request

        chunkedDropsList.forEach { outer ->
            val sj = StringJoiner(",", "[", "]")
            outer.forEach {
                val detailsRequestBody =
                    "{\"operationName\":\"DropCampaignDetails\",\"variables\":{\"dropID\": \"${it}\", \"channelLogin\": \"${channelLogin}\"},\"extensions\":{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"14b5e8a50777165cfc3971e1d93b4758613fe1c817d5542c398dce70b7a45c05\"}}}"
                sj.add(detailsRequestBody)
            }

            val campaignDetails = getCampaignDetails(sj.toString(), oauth, clientId)
            campaignDetails.forEach {
                val campaign = it.data.user.dropCampaign
                val preReqMap = HashMap<String, MutableList<String>>().withDefault { mutableListOf() }

                campaign.timeBasedDrops.forEach { dropItem ->
                    val benefit = dropItem.benefitEdges[0] // Have not yet seen a TimeBasedDrops entity with more than one benefit edge.
                    DropBenefits.insertIgnore { col ->
                        col[id] = dropItem.id
                        col[name] = benefit.benefit.name
                        col[dropId] = campaign.id
                        col[requiredMinutesWatched] = dropItem.requiredMinutesWatched
                        col[entitlementLimit] = benefit.entitlementLimit
                        col[benefitArtUrl] = benefit.benefit.imageAssetURL
                    }

                    if (dropItem.preconditionDrops != null) {
                        dropItem.preconditionDrops.forEach { preReq ->
                            if (preReqMap[dropItem.id].isNullOrEmpty()) {
                                preReqMap[dropItem.id] = mutableListOf(preReq.id)
                            } else {
                                preReqMap[dropItem.id]?.add(preReq.id)
                            }
                        }
                    }
                }

                preReqMap.forEach { (drop, preReqDrops) ->
                    preReqDrops.forEach { preReq ->
                        DropBenefitPreReqs.insertIgnore { col ->
                            col[benefitId] = drop
                            col[preReqBenefitId] = preReq
                        }
                    }
                }
            }
        }

    }
}

fun getCampaignDetails(requestBody: String, oauth: String, clientId: String): List<DetailsApiResponse> {
    val (_, _, result) = "https://gql.twitch.tv/gql".httpPost()
        .body(requestBody)
        .header(Headers.AUTHORIZATION, oauth)
        .header("Client-ID", clientId)
        .responseObject<List<DetailsApiResponse>>()

    return result.get()
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