/*
 * Copyright Red Hat, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Red Hat trademarks are not licensed under GPLv3. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.subscriptions.resource;

import static org.candlepin.subscriptions.utilization.api.model.ProductId.RHEL;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import javax.ws.rs.core.Response;
import org.candlepin.subscriptions.db.AccountListSource;
import org.candlepin.subscriptions.db.SubscriptionCapacityRepository;
import org.candlepin.subscriptions.db.model.Granularity;
import org.candlepin.subscriptions.db.model.ServiceLevel;
import org.candlepin.subscriptions.db.model.SubscriptionCapacity;
import org.candlepin.subscriptions.db.model.Usage;
import org.candlepin.subscriptions.exception.SubscriptionsException;
import org.candlepin.subscriptions.resteasy.PageLinkCreator;
import org.candlepin.subscriptions.security.WithMockRedHatPrincipal;
import org.candlepin.subscriptions.tally.AccountListSourceException;
import org.candlepin.subscriptions.utilization.api.model.CapacityReport;
import org.candlepin.subscriptions.utilization.api.model.CapacitySnapshot;
import org.candlepin.subscriptions.utilization.api.model.GranularityType;
import org.candlepin.subscriptions.utilization.api.model.ServiceLevelType;
import org.candlepin.subscriptions.utilization.api.model.UsageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@WithMockRedHatPrincipal("123456")
@ActiveProfiles({"api", "test"})
class CapacityResourceTest {

  private static final OffsetDateTime min = OffsetDateTime.now().minusDays(4);
  private static final OffsetDateTime max = OffsetDateTime.now().plusDays(4);

  @MockBean SubscriptionCapacityRepository repository;

  @MockBean PageLinkCreator pageLinkCreator;

  @MockBean AccountListSource accountListSource;

  @Autowired CapacityResource resource;

  @BeforeEach
  public void setupTests() throws AccountListSourceException {
    when(accountListSource.containsReportingAccount("account123456")).thenReturn(true);
  }

  @Test
  void testShouldUseQueryBasedOnHeaderAndParameters() {
    SubscriptionCapacity capacity = new SubscriptionCapacity();
    capacity.setBeginDate(min);
    capacity.setEndDate(max);

    when(repository.findByOwnerAndProductId(
            "owner123456", RHEL.toString(), ServiceLevel._ANY, Usage._ANY, min, max))
        .thenReturn(Collections.singletonList(capacity));

    CapacityReport report =
        resource.getCapacityReport(RHEL, GranularityType.DAILY, min, max, null, null, null, null);

    assertEquals(9, report.getData().size());
  }

  @Test
  void testShouldUseSlaQueryParam() {
    SubscriptionCapacity capacity = new SubscriptionCapacity();
    capacity.setBeginDate(min);
    capacity.setEndDate(max);

    when(repository.findByOwnerAndProductId(
            "owner123456", RHEL.toString(), ServiceLevel.PREMIUM, Usage._ANY, min, max))
        .thenReturn(Collections.singletonList(capacity));

    CapacityReport report =
        resource.getCapacityReport(
            RHEL, GranularityType.DAILY, min, max, null, null, ServiceLevelType.PREMIUM, null);

    assertEquals(9, report.getData().size());
  }

  @Test
  void testShouldUseUsageQueryParam() {
    SubscriptionCapacity capacity = new SubscriptionCapacity();
    capacity.setBeginDate(min);
    capacity.setEndDate(max);

    when(repository.findByOwnerAndProductId(
            "owner123456", RHEL.toString(), ServiceLevel._ANY, Usage.PRODUCTION, min, max))
        .thenReturn(Collections.singletonList(capacity));

    CapacityReport report =
        resource.getCapacityReport(
            RHEL, GranularityType.DAILY, min, max, null, null, null, UsageType.PRODUCTION);

    assertEquals(9, report.getData().size());
  }

  @Test
  void testShouldTreatEmptySlaAsNull() {
    SubscriptionCapacity capacity = new SubscriptionCapacity();
    capacity.setBeginDate(min);
    capacity.setEndDate(max);

    when(repository.findByOwnerAndProductId(
            "owner123456", RHEL.toString(), ServiceLevel._ANY, Usage._ANY, min, max))
        .thenReturn(Collections.singletonList(capacity));

    CapacityReport report =
        resource.getCapacityReport(
            RHEL, GranularityType.DAILY, min, max, null, null, ServiceLevelType.EMPTY, null);

    assertEquals(9, report.getData().size());
  }

  @Test
  void testShouldTreatEmptyUsageAsNull() {
    SubscriptionCapacity capacity = new SubscriptionCapacity();
    capacity.setBeginDate(min);
    capacity.setEndDate(max);

    when(repository.findByOwnerAndProductId(
            "owner123456", RHEL.toString(), ServiceLevel._ANY, Usage._ANY, min, max))
        .thenReturn(Collections.singletonList(capacity));

    CapacityReport report =
        resource.getCapacityReport(
            RHEL, GranularityType.DAILY, min, max, null, null, null, UsageType.EMPTY);

    assertEquals(9, report.getData().size());
  }

  @Test
  void testShouldCalculateCapacityBasedOnMultipleSubscriptions() {
    SubscriptionCapacity capacity = new SubscriptionCapacity();
    capacity.setVirtualSockets(5);
    capacity.setPhysicalSockets(2);
    capacity.setVirtualCores(20);
    capacity.setPhysicalCores(8);
    capacity.setBeginDate(min.truncatedTo(ChronoUnit.DAYS).minusSeconds(1));
    capacity.setEndDate(max);

    SubscriptionCapacity capacity2 = new SubscriptionCapacity();
    capacity2.setVirtualSockets(7);
    capacity2.setPhysicalSockets(11);
    capacity2.setVirtualCores(14);
    capacity2.setPhysicalCores(22);
    capacity2.setBeginDate(min.truncatedTo(ChronoUnit.DAYS).minusSeconds(1));
    capacity2.setEndDate(max);

    when(repository.findByOwnerAndProductId("owner123456", RHEL.toString(), null, null, min, max))
        .thenReturn(Arrays.asList(capacity, capacity2));

    CapacityReport report =
        resource.getCapacityReport(RHEL, GranularityType.DAILY, min, max, null, null, null, null);

    CapacitySnapshot capacitySnapshot = report.getData().get(0);
    assertEquals(12, capacitySnapshot.getHypervisorSockets().intValue());
    assertEquals(13, capacitySnapshot.getPhysicalSockets().intValue());
    assertEquals(34, capacitySnapshot.getHypervisorCores().intValue());
    assertEquals(30, capacitySnapshot.getPhysicalCores().intValue());
  }

  @Test
  void testShouldThrowExceptionOnBadOffset() {
    SubscriptionsException e =
        assertThrows(
            SubscriptionsException.class,
            () -> {
              resource.getCapacityReport(RHEL, GranularityType.DAILY, min, max, 11, 10, null, null);
            });
    assertEquals(Response.Status.BAD_REQUEST, e.getStatus());
  }

  @Test
  void testShouldRespectOffsetAndLimit() {
    SubscriptionCapacity capacity = new SubscriptionCapacity();
    capacity.setBeginDate(min);
    capacity.setEndDate(max);

    when(repository.findByOwnerAndProductId(
            "owner123456", RHEL.toString(), ServiceLevel._ANY, Usage._ANY, min, max))
        .thenReturn(Collections.singletonList(capacity));

    CapacityReport report =
        resource.getCapacityReport(RHEL, GranularityType.DAILY, min, max, 1, 1, null, null);

    assertEquals(1, report.getData().size());
    assertEquals(
        OffsetDateTime.now().minusDays(3).truncatedTo(ChronoUnit.DAYS),
        report.getData().get(0).getDate());
  }

  @Test
  @WithMockRedHatPrincipal("1111")
  void testAccessDeniedWhenAccountIsNotWhitelisted() {
    assertThrows(
        AccessDeniedException.class,
        () -> {
          resource.getCapacityReport(RHEL, GranularityType.DAILY, min, max, null, null, null, null);
        });
  }

  @Test
  @WithMockRedHatPrincipal(
      value = "123456",
      roles = {})
  void testAccessDeniedWhenUserIsNotAnAdmin() {
    assertThrows(
        AccessDeniedException.class,
        () -> {
          resource.getCapacityReport(RHEL, GranularityType.DAILY, min, max, null, null, null, null);
        });
  }

  @Test
  void testGetCapacitiesWeekly() {
    SubscriptionCapacity capacity = new SubscriptionCapacity();
    OffsetDateTime begin = OffsetDateTime.parse("2020-12-03T10:15:30+00:00");
    OffsetDateTime end = OffsetDateTime.parse("2020-12-17T10:15:30+00:00");
    capacity.setBeginDate(begin);
    capacity.setEndDate(end);

    when(repository.findByOwnerAndProductId(
            "owner123456", RHEL.toString(), ServiceLevel._ANY, Usage.PRODUCTION, begin, end))
        .thenReturn(Collections.singletonList(capacity));

    List<CapacitySnapshot> actual =
        resource.getCapacities(
            "owner123456",
            RHEL,
            ServiceLevel.STANDARD,
            Usage.PRODUCTION,
            Granularity.WEEKLY,
            begin,
            end);

    // Add one because we generate reports including both endpoints on the timeline
    long expected = ChronoUnit.WEEKS.between(begin, end) + 1;
    assertEquals(expected, actual.size());
  }

  @Test
  void testShouldCalculateCapacityWithUnlimitedUsage() {
    SubscriptionCapacity capacity = new SubscriptionCapacity();
    capacity.setHasUnlimitedUsage(true);
    capacity.setBeginDate(min.truncatedTo(ChronoUnit.DAYS).minusSeconds(1));
    capacity.setEndDate(max);

    when(repository.findByOwnerAndProductId("owner123456", RHEL.toString(), null, null, min, max))
        .thenReturn(Arrays.asList(capacity));

    CapacityReport report =
        resource.getCapacityReport(RHEL, GranularityType.DAILY, min, max, null, null, null, null);

    CapacitySnapshot capacitySnapshot = report.getData().get(0);
    assertTrue(capacitySnapshot.getHasInfiniteQuantity());
  }

  static Stream<Arguments> usageLists() {
    SubscriptionCapacity limited = new SubscriptionCapacity();
    limited.setHasUnlimitedUsage(false);
    limited.setPhysicalCores(4);
    limited.setBeginDate(min.truncatedTo(ChronoUnit.DAYS).minusSeconds(1));
    limited.setEndDate(max);
    SubscriptionCapacity unlimited = new SubscriptionCapacity();
    unlimited.setHasUnlimitedUsage(true);
    unlimited.setBeginDate(min.truncatedTo(ChronoUnit.DAYS).minusSeconds(1));
    unlimited.setEndDate(max);

    return Stream.of(
        arguments(Arrays.asList(unlimited, limited)), arguments(Arrays.asList(limited, unlimited)));
  }

  @ParameterizedTest
  @MethodSource("usageLists")
  void testShouldCalculateCapacityRegardlessOfUsageSeenFirst(List<SubscriptionCapacity> usages) {
    when(repository.findByOwnerAndProductId("owner123456", RHEL.toString(), null, null, min, max))
        .thenReturn(usages);

    CapacityReport report =
        resource.getCapacityReport(RHEL, GranularityType.DAILY, min, max, null, null, null, null);

    CapacitySnapshot capacitySnapshot = report.getData().get(0);
    assertTrue(capacitySnapshot.getHasInfiniteQuantity());
  }
}
