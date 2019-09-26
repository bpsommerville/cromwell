package cromwell.services.database

import java.time.OffsetDateTime

import cromwell.core.Tags.DbmsTest
import cromwell.database.slick.MetadataSlickDatabase
import cromwell.database.sql.tables.{MetadataEntry, WorkflowMetadataSummaryEntry}
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

class MetadataSlickDatabaseSpec extends FlatSpec with Matchers with ScalaFutures {

  DatabaseSystem.All foreach { databaseSystem =>

    implicit val ec = ExecutionContext.global

    behavior of s"MetadataSlickDatabase on ${databaseSystem.name}"

    lazy val database: MetadataSlickDatabase with TestSlickDatabase = DatabaseTestKit.initializedDatabaseFromSystem(MetadataDatabaseType, databaseSystem)
    import database.dataAccess.driver.api._

    import cromwell.database.migration.metadata.table.symbol.MetadataStatement.OffsetDateTimeToSystemTimestamp
    val now = OffsetDateTime.now().toSystemTimestamp

    database.runTestTransaction(
      database.dataAccess.metadataEntries ++= Seq(
        MetadataEntry("workflow id: 3 to delete, 1 label", None, None, None, "someKey", None, None, OffsetDateTime.now().toSystemTimestamp, None),
        MetadataEntry("workflow id: 3 to delete, 1 label", None, None, None, "someKey", None, None, OffsetDateTime.now().toSystemTimestamp, None),
        MetadataEntry("workflow id: 3 to delete, 1 label", None, None, None, "someKey", None, None, OffsetDateTime.now().toSystemTimestamp, None),
        MetadataEntry("workflow id: 3 to delete, 1 label", None, None, None, "label:dontDeleteMe", None, None, OffsetDateTime.now().toSystemTimestamp, None),
      )
    )

    database.runTestTransaction(
      database.dataAccess.workflowMetadataSummaryEntries ++= Seq(
        WorkflowMetadataSummaryEntry("workflow id: I am not a root workflow", Option("workflow name"), Option("Succeeded"), Option(now), Option(now), Option(now), Option("I have a parent"), Option("I have a parent")),
        WorkflowMetadataSummaryEntry("workflow id: 3 to delete, 1 label", Option("workflow name"), Option("Succeeded"), Option(now), Option(now), Option(now), None, None),
      )
    )

    // attempt deleting root workflow that does not exist
    it should "error when deleting a root workflow that does not exist" taggedAs DbmsTest in {
      val delete = database.deleteNonLabelMetadataForWorkflow("does not exist")

      delete.failed.futureValue(Timeout(10.seconds)).getMessage should be("""Programmer error: attempted to delete metadata rows for non-existent root workflow "does not exist"""")
    }

    // attempt deleting subworkflow id. should error
    it should "error when deleting a subworkflow" taggedAs DbmsTest in {
      val delete = database.deleteNonLabelMetadataForWorkflow("workflow id: I am not a root workflow")
      delete.failed.futureValue(Timeout(10.seconds)).getMessage should be("""Programmer error: attempted to delete metadata rows for non-root workflow "workflow id: I am not a root workflow"""")
    }

    // attempt deleting a subworkflow that has subworkflows itself. should error

    // attempt deleting . should delete expected row count
    it should "delete the right number of rows for a root workflow id w/o subworkflows" taggedAs DbmsTest in {
      val delete = database.deleteNonLabelMetadataForWorkflow("workflow id: 3 to delete, 1 label")
      delete.futureValue(Timeout(10.seconds)) should be(3)
    }

    // attempt deleting root workflow id with subworkflows. should delete expected row count

    // attempt deleting root workflow id with subworkflows that have subworkflows. should delete expected row count

    it should "clean up & close the database" taggedAs DbmsTest in {
      // Not relevant in Travis where all state gets nuked but useful for testing locally
      database.runTestTransaction(database.dataAccess.metadataEntries.delete)
      database.runTestTransaction(database.dataAccess.workflowMetadataSummaryEntries.delete)

      database.close()
    }
  }


}
