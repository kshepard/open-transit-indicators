package com.azavea.opentransit.indicators

import geotrellis.vector._

import com.azavea.opentransit.database.{ BoundariesTable, RoadsTable }
import scala.slick.jdbc.JdbcBackend.Session

import grizzled.slf4j.Logging

// Calculation request parameters
case class IndicatorCalculationRequest(
  token: String,
  version: String,
  povertyLine: Double,
  nearbyBufferDistance: Double,
  maxCommuteTime: Int,
  maxWalkTime: Int,
  cityBoundaryId: Int,
  regionBoundaryId: Int,
  averageFare: Double,
  samplePeriods: List[SamplePeriod]
) extends Logging {
  // There's probably a better place for these database fetches. Especially if
  // the info is used between various requests and some indicators that could
  // start calculation will have to wait on this to happen. But we can do them here
  // and that keeps the database from having to be injected into the Indicators,
  // which is a big win for modularity. Global state objects like this are icky.
  // There's surely a better way to get the information that any one Indicator needs
  // to it without having to pass everything to everyone.


  def toParams(systems: Map[SamplePeriod, TransitSystem])(implicit session: Session): Map[SamplePeriod, IndicatorParams] = {
      val stopBuffers = StopBuffers(systems, this.nearbyBufferDistance)
      systems.map{case (period, transitSystem) =>

        period -> new IndicatorParams with StopBuffers {

          def bufferForStop(stop: Stop): Polygon = stopBuffers.bufferForStop(stop)
          def bufferForPeriod(period: SamplePeriod): MultiPolygon = stopBuffers.bufferForPeriod(period)
          def totalBuffer: MultiPolygon = stopBuffers.totalBuffer

          val settings =
            IndicatorSettings(
              this.povertyLine,
              this.nearbyBufferDistance,
              this.maxCommuteTime,
              this.maxWalkTime,
              this.averageFare
          )
          val cityBoundary = Boundaries.cityBoundary(this.cityBoundaryId)
          val regionBoundary = Boundaries.cityBoundary(this.regionBoundaryId)

          // val populationUnder = Demographics(db)
          val totalRoadLength = RoadLength.totalRoadLength
        }
      }
  }
}
