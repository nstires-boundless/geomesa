/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.process.query

import com.typesafe.scalalogging.LazyLogging
import org.geotools.data.Query
import org.geotools.data.collection.ListFeatureCollection
import org.geotools.data.simple.{SimpleFeatureCollection, SimpleFeatureSource}
import org.geotools.process.factory.{DescribeParameter, DescribeProcess, DescribeResult}
import org.geotools.util.NullProgressListener
import org.locationtech.geomesa.process.{FeatureResult, GeoMesaProcess, GeoMesaProcessVisitor}
import org.locationtech.geomesa.utils.collection.SelfClosingIterator
import org.locationtech.geomesa.utils.geotools.Conversions.{RichGeometry, _}
import org.opengis.feature.Feature
import org.opengis.feature.simple.SimpleFeature
import org.opengis.filter.Filter

import scala.collection.JavaConversions._

@DescribeProcess(
  title = "Geomesa-enabled Proximity Search",
  description = "Performs a proximity search on a Geomesa feature collection using another feature collection as input"
)
class ProximitySearchProcess extends GeoMesaProcess with LazyLogging {

  @DescribeResult(description = "Output feature collection")
  def execute(
               @DescribeParameter(
                 name = "inputFeatures",
                 description = "Input feature collection that defines the proximity search")
               inputFeatures: SimpleFeatureCollection,

               @DescribeParameter(
                 name = "dataFeatures",
                 description = "The data set to query for matching features")
               dataFeatures: SimpleFeatureCollection,

               @DescribeParameter(
                 name = "bufferDistance",
                 description = "Buffer size in meters")
               bufferDistance: java.lang.Double

               ): SimpleFeatureCollection = {

    logger.debug(s"Attempting Geomesa Proximity Search on collection type ${dataFeatures.getClass.getName}")

    val visitor = new ProximityVisitor(inputFeatures, dataFeatures, bufferDistance.doubleValue())
    dataFeatures.accepts(visitor, new NullProgressListener)
    visitor.getResult.results
  }
}

class ProximityVisitor(inputFeatures: SimpleFeatureCollection,
                       dataFeatures: SimpleFeatureCollection,
                       bufferInMeters: Double) extends GeoMesaProcessVisitor with LazyLogging {

  import org.locationtech.geomesa.filter.{ff, mergeFilters}

  private lazy val manualFilter = dwithinFilters(true)
  private val manualVisitResults = new ListFeatureCollection(dataFeatures.getSchema)

  private var resultCalc = FeatureResult(manualVisitResults)

  // non-optimized visit
  // here we use degrees for our filters since we are manually evaluating them.
  override def visit(feature: Feature): Unit = {
    val sf = feature.asInstanceOf[SimpleFeature]
    if (manualFilter.evaluate(sf)) {
      manualVisitResults.add(sf)
    }
  }

  override def getResult: FeatureResult = resultCalc

  override def execute(source: SimpleFeatureSource, query: Query): Unit = {
    logger.debug(s"Running Geomesa Proximity Search on source type ${source.getClass.getName}")
    val combinedFilter = mergeFilters(query.getFilter, dwithinFilters(false))
    resultCalc = FeatureResult(source.getFeatures(combinedFilter))
  }

  private def dwithinFilters(convertToDegrees: Boolean): Filter = {
    val geomProperty = ff.property(dataFeatures.getSchema.getGeometryDescriptor.getName)
    val geomFilters = SelfClosingIterator(inputFeatures.features).map { sf =>
      // for manual visits, the filter always uses native distance units (i.e. degrees)
      // for optimized visits, we handle the 'meters' unit correctly in our query planning
      val dist = if (convertToDegrees) { sf.geometry.distanceDegrees(bufferInMeters) } else { bufferInMeters }
      ff.dwithin(geomProperty, ff.literal(sf.geometry), dist, "meters")
    }
    ff.or(geomFilters.toSeq)
  }
}
