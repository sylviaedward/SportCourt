package com.sportcourt.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.sportcourt.entity.Venue;
import com.sportcourt.service.IVenueService;
import com.sportcourt.utils.CacheClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class CacheChangeProjectorTest {
  @Test
  void replayReadsLatestDatabaseStateInsteadOfWritingOldEventPayload() throws Exception {
    CacheClient cache = mock(CacheClient.class);
    ObjectProvider<IVenueService> provider = mock(ObjectProvider.class);
    IVenueService venues = mock(IVenueService.class);
    when(provider.getObject()).thenReturn(venues);
    when(venues.getById(5L)).thenReturn(new Venue().setId(5L).setName("latest"));
    when(cache.queryWithMutex(
            eq("cache:venue:"), eq(5L), eq(Venue.class), any(), eq(20L), eq(TimeUnit.MINUTES)))
        .thenAnswer(
            invocation -> {
              Function<Long, Venue> read = invocation.getArgument(3);
              assertEquals("latest", read.apply(5L).getName());
              return read.apply(5L);
            });
    CacheChangeProjector projector =
        new CacheChangeProjector(
            cache, provider, mock(ObjectProvider.class), new SimpleMeterRegistry());
    ReflectionTestUtils.setField(projector, "schema", "test");
    CanalEntry.RowData row =
        CanalEntry.RowData.newBuilder()
            .addAfterColumns(column("id", "5"))
            .addAfterColumns(column("name", "old-event"))
            .build();
    projector.projectEntryChanges(entry("tb_venue", CanalEntry.EventType.UPDATE, row));
    projector.projectEntryChanges(entry("tb_venue", CanalEntry.EventType.UPDATE, row));
    verify(cache, times(2)).invalidate("cache:venue:5");
    verify(cache, times(2))
        .queryWithMutex(
            eq("cache:venue:"), eq(5L), eq(Venue.class), any(), eq(20L), eq(TimeUnit.MINUTES));
  }

  @Test
  void deletesOnlyInvalidateAndStockNeverChangesExecutionInventory() throws Exception {
    CacheClient cache = mock(CacheClient.class);
    CacheChangeProjector projector =
        new CacheChangeProjector(
            cache,
            mock(ObjectProvider.class),
            mock(ObjectProvider.class),
            new SimpleMeterRegistry());
    ReflectionTestUtils.setField(projector, "schema", "test");
    projector.projectEntryChanges(
        entry(
            "tb_venue",
            CanalEntry.EventType.DELETE,
            CanalEntry.RowData.newBuilder().addBeforeColumns(column("id", "5")).build()));
    verify(cache).invalidate("cache:venue:5");
    projector.projectEntryChanges(
        entry(
            "tb_golden_slot_booking_pass",
            CanalEntry.EventType.UPDATE,
            CanalEntry.RowData.newBuilder()
                .addAfterColumns(column("booking_pass_id", "5"))
                .addAfterColumns(column("stock", "999"))
                .build()));
    verifyNoMoreInteractions(cache);
  }

  @Test
  void ddlInvalidatesMetadataNamespacesAndMalformedIdentityFailsForReplay() throws Exception {
    CacheClient cache = mock(CacheClient.class);
    CacheChangeProjector projector =
        new CacheChangeProjector(
            cache,
            mock(ObjectProvider.class),
            mock(ObjectProvider.class),
            new SimpleMeterRegistry());
    ReflectionTestUtils.setField(projector, "schema", "test");
    CanalEntry.RowChange ddl =
        CanalEntry.RowChange.newBuilder()
            .setIsDdl(true)
            .setEventType(CanalEntry.EventType.ALTER)
            .build();
    projector.projectEntryChanges(
        CanalEntry.Entry.newBuilder()
            .setEntryType(CanalEntry.EntryType.ROWDATA)
            .setHeader(
                CanalEntry.Header.newBuilder()
                    .setSchemaName("test")
                    .setTableName("tb_booking_pass"))
            .setStoreValue(ddl.toByteString())
            .build());
    verify(cache).invalidatePrefix("cache:bookingPass-meta:");
    verify(cache).invalidatePrefix("cache:bookingPass-list:");
    assertThrows(
        IllegalStateException.class,
        () ->
            projector.projectEntryChanges(
                entry(
                    "tb_venue",
                    CanalEntry.EventType.UPDATE,
                    CanalEntry.RowData.newBuilder().build())));
  }

  private static CanalEntry.Column column(String name, String value) {
    return CanalEntry.Column.newBuilder().setName(name).setValue(value).build();
  }

  private static CanalEntry.Entry entry(
      String table, CanalEntry.EventType type, CanalEntry.RowData row) {
    return CanalEntry.Entry.newBuilder()
        .setEntryType(CanalEntry.EntryType.ROWDATA)
        .setHeader(CanalEntry.Header.newBuilder().setSchemaName("test").setTableName(table))
        .setStoreValue(
            CanalEntry.RowChange.newBuilder()
                .setEventType(type)
                .addRowDatas(row)
                .build()
                .toByteString())
        .build();
  }
}
