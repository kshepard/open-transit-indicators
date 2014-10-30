package com.azavea.opentransit.database

import geotrellis.vector._
import geotrellis.slick._

import com.azavea.gtfs._

import scala.slick.driver.{JdbcDriver, JdbcProfile, PostgresDriver}
import scala.slick.jdbc.{StaticQuery => Q}

import com.azavea.opentransit.indicators._


// case class IndicatorResultDB(
//   indicatorId: String,
//   samplePeriodType: String,
//   aggregation: String,
//   value: Double,
//   geom: Geometry,
//   calculationJob: Int,
//   routeId: String = "",
//   routeType: Int,
//   cityBounded: Boolean = false
// )

import com.github.nscala_time.time.Imports._

trait IndicatorsTable {
  import PostgresDriver.simple._
  private val gisSupport = new PostGisProjectionSupport(PostgresDriver)
  import gisSupport._

  def indicatorResultToTuple(result: IndicatorResultContainer): Option[(String, Int, String,
    Double, Projected[Geometry], Int, String, Int, Boolean)] = {
    val aggregationString = result.aggregation match {
      case RouteAggregate => "route"
      case RouteTypeAggregate => "mode"
      case SystemAggregate => "system"
    }
    val routeId = result.routeType match {
      case Some(t) => t.id
      case None => 0
    }
    Option(result.indicatorId, 1, aggregationString,
      result.value, Projected(result.geom, 4326), result.calculationJob,
      result.routeId, routeId, result.cityBounded)
  }

  def indicatorResultFromTuple(input: (String, Int, String,
    Double, Projected[Geometry], Int, String, Int, Boolean)): IndicatorResultContainer = {
    val dummySamplePeriod = SamplePeriod(1, "alltime",
      new LocalDateTime("01-01-01T00:00:00.000"),
      new LocalDateTime("2014-05-01T08:00:00.000")
    )
    input match {
      case (indicatorId, samplePeriodType, aggregationString,
        value, geom, calculationJob, routeId, routeType, cityBounded) => {
        new IndicatorResultContainer(indicatorId, dummySamplePeriod, SystemAggregate, value,
          geom, calculationJob, routeId, Option(RouteType(routeType.toInt)), cityBounded)
      }
    }
  }

  class Indicators(tag: Tag) extends Table[IndicatorResultContainer](tag, "transit_indicators_indicator") {
    def indicatorId = column[String]("type")
    def samplePeriodType = column[Int]("sample_period_id")
    def aggregation = column[String]("aggregation")
    def value = column[Double]("value")
    def geom = column[Projected[Geometry]]("the_geom")
    def calculationJob = column[Int]("calculation_job_id")
    def routeId = column[String]("route_id")
    def routeType = column[Int]("route_type")
    def cityBounded = column[Boolean]("city_bounded")

    def * = (indicatorId, samplePeriodType, aggregation,
      value, geom, calculationJob, routeId, routeType, cityBounded) <> (indicatorResultFromTuple,
       indicatorResultToTuple)
  }

  def indicatorsTable = TableQuery[Indicators]

}
