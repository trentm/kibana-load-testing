package org.kibanaLoadTest.scenario

import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._
import org.kibanaLoadTest.helpers.Helper

import java.util.Calendar

object Dashboard {
  // bsearch1.json ... bsearch9.json
  private val bSearchPayloadSeq = Seq(1, 2, 3, 4, 5, 6, 7, 8, 9)

  def load(baseUrl: String, headers: Map[String, String]): ChainBuilder = {
    exec(// unique search sessionId for each virtual user
      session => session.set("searchSessionId", Helper.generateUUID)
    ).exec(// unique date picker start time for each virtual user
      session => session.set("startTime", Helper.getDate(Calendar.DAY_OF_MONTH, -Helper.getRandomNumber(3, 15)))
    ).exec(// unique date picker end time for each virtual user
      session => session.set("endTime", Helper.getDate(Calendar.DAY_OF_MONTH, 0))
    ).exec(
      http("query dashboards list")
        .get("/api/saved_objects/_find")
        .queryParam("default_search_operator", "AND")
        .queryParam("has_reference", "[]")
        .queryParam("page", "1")
        .queryParam("per_page", "1000")
        .queryParam("search_fields", "title^3")
        .queryParam("search_fields", "description")
        .queryParam("type", "dashboard")
        .headers(headers)
        .header("Referer", baseUrl + "/app/dashboards")
        .check(status.is(200))
        .check(
          jsonPath("$.saved_objects[0].id").find.saveAs("dashboardId")
        )
        .check(
          jsonPath(
            "$.saved_objects[0].references[?(@.type=='visualization')]"
          ).findAll
            .transform(_.map(_.replaceAll("\"name(.+?),", "")))
            .saveAs("vizVector")
        )
        .check(
          jsonPath(
            "$.saved_objects[0].references[?(@.type=='map' || @.type=='search')]"
          ).findAll
            .transform(
              _.map(_.replaceAll("\"name(.+?),", ""))
            ) //remove name attribute
            .saveAs("searchAndMapVector")
        )
    ).exec(
        http("query index pattern")
          .get("/api/saved_objects/_find")
          .queryParam("fields", "title")
          .queryParam("per_page", "10000")
          .queryParam("type", "index-pattern")
          .headers(headers)
          .header("Referer", baseUrl + "/app/dashboards")
          .check(status.is(200))
          .check(
            jsonPath("$.saved_objects[?(@.type=='index-pattern')].id")
              .saveAs("indexPatternId")
          )
      )
      .exitBlockOnFail {
        exec(
          http("query dashboard panels")
            .post("/api/saved_objects/_bulk_get")
            .body(
              StringBody("[{\"id\":\"${dashboardId}\",\"type\":\"dashboard\"}]")
            )
            .headers(headers)
            .header("Referer", baseUrl + "/app/dashboards")
            .check(status.is(200))
        ).exec(session =>
          //convert Vector -> String for visualizations request
          session.set(
            "vizListString",
            session("vizVector").as[Seq[String]].mkString(",")
          )
        ).exec(session =>
          //convert Vector -> String for search&map request
          session.set(
            "searchAndMapString",
            session("searchAndMapVector").as[Seq[String]].mkString(",")
          )
        ).exec(
          http("query visualizations")
            .post("/api/saved_objects/_bulk_get")
            .body(StringBody("[${vizListString}, {\"id\":\"${indexPatternId}\", \"type\":\"index-pattern\"}]"))
            .asJson
            .headers(headers)
            .header("Referer", baseUrl + "/app/dashboards")
            .check(status.is(200))
        ).exec(
          http("query search & map")
            .post("/api/saved_objects/_bulk_get")
            .body(StringBody("""[ ${searchAndMapString} ]""".stripMargin))
            .asJson
            .headers(headers)
            .header("Referer", baseUrl + "/app/dashboards")
            .check(status.is(200))
        ).exec(
          http("query index pattern meta fields")
          .get("/api/index_patterns/_fields_for_wildcard")
            .queryParam("pattern", "kibana_sample_data_ecommerce")
            .queryParam("meta_fields", "_source")
            .queryParam("meta_fields", "_id")
            .queryParam("meta_fields", "_type")
            .queryParam("meta_fields", "_index")
            .queryParam("meta_fields", "_score")
            .headers(headers)
            .header("Referer", baseUrl + "/app/dashboards")
            .check(status.is(200))
        ).exec(
          http("query index pattern search fields")
            .get("/api/saved_objects/_find")
            .queryParam("fields", "title")
            .queryParam("per_page", "10")
            .queryParam("search", "kibana_sample_data_ecommerce")
            .queryParam("search_fields", "title")
            .queryParam("type", "index-pattern")
            .headers(headers)
            .header("Referer", baseUrl + "/app/dashboards")
            .check(status.is(200))
        ).exec(
          http("query input control settings")
            .get("/api/input_control_vis/settings")
            .headers(headers)
            .header("Referer", baseUrl + "/app/dashboards")
            .check(status.is(200))
        ).exec(
          http("query timeseries data")
            .post("/api/metrics/vis/data")
            .body(ElFileBody("data/timeSeriesPayload.json"))
            .asJson
            .headers(headers)
            .header("Referer", baseUrl + "/app/dashboards")
            .check(status.is(200))
        ).exec(
          http("query gauge data")
            .post("/api/metrics/vis/data")
            .body(ElFileBody("data/gaugePayload.json"))
            .asJson
            .headers(headers)
            .header("Referer", baseUrl + "/app/dashboards")
            .check(status.is(200))
        ).foreach(bSearchPayloadSeq, "index") {
          exec(session => {
            session.set(
              "payloadString",
              Helper.loadJsonString(s"data/bsearch${session("index").as[Int]}.json")
            )
          }).exec(
            http("query bsearch ${index}")
              .post("/internal/bsearch")
              .body(StringBody("${payloadString}"))
              .asJson
              .headers(headers)
              .header("Referer", baseUrl + "/app/dashboards")
              .check(status.is(200))
          )
        }
      }
  }
}
