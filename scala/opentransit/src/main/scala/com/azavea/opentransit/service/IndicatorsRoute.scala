package com.azavea.opentransit.service

import com.azavea.opentransit._
import com.azavea.opentransit.CalculationStatus
import com.azavea.opentransit.CalculationStatus._
import com.azavea.opentransit.json._
import com.azavea.opentransit.indicators._

import com.azavea.gtfs._

import com.github.nscala_time.time.Imports._
import com.github.tototoshi.slick.PostgresJodaSupport

import geotrellis.proj4._
import geotrellis.slick._

import scala.slick.driver.PostgresDriver
import scala.slick.jdbc.{GetResult, StaticQuery => Q}
import scala.slick.jdbc.JdbcBackend.{Database, Session, DatabaseDef}
import scala.slick.jdbc.meta.MTable

import spray.http.MediaTypes
import spray.http.StatusCodes.{Created, InternalServerError}
import spray.routing.{ExceptionHandler, HttpService}
import spray.util.LoggingContext
import scala.concurrent._

// JSON support
import spray.json._
import spray.httpx.SprayJsonSupport
import SprayJsonSupport._
import DefaultJsonProtocol._

import com.typesafe.config.{ConfigFactory, Config}

case class IndicatorJob(
  version: String = "",
  status: Map[String, CalculationStatus]
)

trait IndicatorsRoute extends Route { self: DatabaseInstance =>
  val config = ConfigFactory.load
  val dbGeomNameUtm = config.getString("database.geom-name-utm")

  // Endpoint for triggering indicator calculations
  //
  // TODO: Add queue management. Calculation request jobs will be stored
  //   in a table, and calculations will be run one (or more) at a time
  //   in the background via an Actor.
  def indicatorsRoute = {
    path("indicators") {
      post {
        entity(as[IndicatorCalculationRequest]) { request =>
          complete {
            var err: JsObject = null
            TaskQueue.execute {
              try {
                // Load Gtfs records from the database. Load it with UTM projection (column 'geom' in the database)
                val gtfsRecords =
                  db withSession { implicit session =>
                    GtfsRecords.fromDatabase(dbGeomNameUtm)
                  }

                // Perform all indicator calculations, store results and statuses
                CalculateIndicators(request, gtfsRecords, db, new CalculationStatusManager {
                  def indicatorFinished(containerGenerators: Seq[ContainerGenerator]) = {
                    val indicatorResultContainers = containerGenerators.map(_.toContainer(request.version))
                    DjangoClient.postIndicators(request.token, indicatorResultContainers)
                  }

                  def statusChanged(status: Map[String, CalculationStatus]) = {
                    DjangoClient.updateIndicatorJob(request.token, IndicatorJob(request.version, status))
                  }
                })
              } catch {
              case e: Exception =>
                println("Error calculating indicators!")
                println(e.getMessage)
                println(e.getStackTrace.mkString("\n"))
                err = JsObject(
                  "success" -> JsBoolean(false),
                  "message" -> JsString("No GTFS data")
                )

                // update status to indicate indicator calculation failure
                try {
                  DjangoClient.updateIndicatorJob(request.token, IndicatorJob(request.version, Map("alltime" -> CalculationStatus.Failed)))
                } catch {
                  case ex: Exception =>
                    println("Failed to set failure status for indicator calculation job!")
                }
              }
            }

            if (err == null) {
              // return a 201 created
              Created -> JsObject(
                "success" -> JsBoolean(true),
                "message" -> JsString(s"Calculations started (version ${request.version})")
              )
            } else {
              err
            }
          }
        }
      }
    }
  }
}
