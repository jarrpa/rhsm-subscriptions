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
package org.candlepin.subscriptions.tally;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.candlepin.subscriptions.db.AccountServiceInventoryRepository;
import org.candlepin.subscriptions.db.model.*;
import org.candlepin.subscriptions.event.EventController;
import org.candlepin.subscriptions.json.Event;
import org.candlepin.subscriptions.registry.TagMetaData;
import org.candlepin.subscriptions.registry.TagProfile;
import org.candlepin.subscriptions.util.ApplicationClock;
import org.candlepin.subscriptions.util.DateRange;
import org.candlepin.subscriptions.utilization.api.model.BillingProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/** Collects instances and tallies based on hourly metrics. */
public class MetricUsageCollector {
  private static final Logger log = LoggerFactory.getLogger(MetricUsageCollector.class);

  private final AccountServiceInventoryRepository accountServiceInventoryRepository;
  private final EventController eventController;
  private final ApplicationClock clock;
  private final TagProfile tagProfile;

  public MetricUsageCollector(
      TagProfile tagProfile,
      AccountServiceInventoryRepository accountServiceInventoryRepository,
      EventController eventController,
      ApplicationClock clock) {
    this.accountServiceInventoryRepository = accountServiceInventoryRepository;
    this.eventController = eventController;
    this.clock = clock;
    this.tagProfile = tagProfile;
  }

  @Transactional
  public CollectionResult collect(String serviceType, String accountNumber, DateRange range) {
    if (!clock.isHourlyRange(range)) {
      throw new IllegalArgumentException(
          String.format(
              "Start and end dates must be at the top of the hour: [%s -> %s]",
              range.getStartString(), range.getEndString()));
    }

    if (!eventController.hasEventsInTimeRange(
        accountNumber, serviceType, range.getStartDate(), range.getEndDate())) {
      log.info("No event metrics to process for service type {} in range: {}", serviceType, range);
      return null;
    }

    /* load the latest accountServiceInventory state, so we can update host records conveniently */
    AccountServiceInventory accountServiceInventory =
        accountServiceInventoryRepository
            .findById(new AccountServiceInventoryId(accountNumber, serviceType))
            .orElse(new AccountServiceInventory(accountNumber, serviceType));

    /*
    Evaluate latest state to determine if we are doing a recalculation and filter to host records for only
    the product profile we're working on
    */
    Map<String, Host> existingInstances = new HashMap<>();
    OffsetDateTime newestInstanceTimestamp = OffsetDateTime.MIN;
    for (Host host : accountServiceInventory.getServiceInstances().values()) {
      existingInstances.put(host.getInstanceId(), host);
      newestInstanceTimestamp =
          newestInstanceTimestamp.isAfter(host.getLastSeen())
              ? newestInstanceTimestamp
              : host.getLastSeen();
    }
    OffsetDateTime effectiveStartDateTime;
    OffsetDateTime effectiveEndDateTime;
    boolean isRecalculating;
    /*
    We need to recalculate several things if we are re-tallying, namely monthly totals need to be
    cleared and re-updated for each host record
     */
    if (newestInstanceTimestamp.isAfter(range.getStartDate())) {
      effectiveStartDateTime = clock.startOfMonth(range.getStartDate());
      effectiveEndDateTime = clock.endOfCurrentHour();
      log.info(
          "We appear to be retallying; adjusting start and end from [{} : {}] to [{} : {}]",
          range.getStartString(),
          range.getEndString(),
          effectiveStartDateTime,
          effectiveEndDateTime);
      isRecalculating = true;
    } else {
      effectiveStartDateTime = range.getStartDate();
      effectiveEndDateTime = range.getEndDate();
      log.info(
          "New tally! Adjusting start and end from [{} : {}] to [{} : {}]",
          range.getStartString(),
          range.getEndString(),
          effectiveStartDateTime,
          effectiveEndDateTime);
      isRecalculating = false;
    }

    if (isRecalculating) {
      log.info("Clearing monthly totals for {} instances", existingInstances.size());
      existingInstances
          .values()
          .forEach(
              instance ->
                  instance.clearMonthlyTotals(effectiveStartDateTime, effectiveEndDateTime));
    }

    Map<OffsetDateTime, AccountUsageCalculation> accountCalcs = new HashMap<>();
    for (OffsetDateTime offset = effectiveStartDateTime;
        offset.isBefore(effectiveEndDateTime);
        offset = offset.plusHours(1)) {
      AccountUsageCalculation accountUsageCalculation =
          collectHour(accountServiceInventory, offset);
      if (accountUsageCalculation != null && !accountUsageCalculation.getKeys().isEmpty()) {
        accountCalcs.put(offset, accountUsageCalculation);
      }
    }
    accountServiceInventoryRepository.save(accountServiceInventory);

    return new CollectionResult(
        new DateRange(effectiveStartDateTime, effectiveEndDateTime), accountCalcs, isRecalculating);
  }

  @Transactional
  public AccountUsageCalculation collectHour(
      AccountServiceInventory accountServiceInventory, OffsetDateTime startDateTime) {
    Optional<TagMetaData> serviceTypeMeta =
        tagProfile.getTagMetaDataByServiceType(accountServiceInventory.getServiceType());
    OffsetDateTime endDateTime = startDateTime.plusHours(1);

    Map<String, List<Event>> eventToHostMapping =
        eventController
            .fetchEventsInTimeRangeByServiceType(
                accountServiceInventory.getAccountNumber(),
                accountServiceInventory.getServiceType(),
                startDateTime,
                endDateTime)
            // We group fetched events by instanceId so that we can clear the measurements
            // on first access, if the instance already exists for the accountServiceInventory.
            .collect(Collectors.groupingBy(Event::getInstanceId));

    Map<String, Host> thisHoursInstances = new HashMap<>();
    eventToHostMapping.forEach(
        (instanceId, events) -> {
          Host existing = accountServiceInventory.getServiceInstances().get(instanceId);
          Host host = existing == null ? new Host() : existing;
          // Clear all measurements before processing the events so that we do
          // not add old measurements to the new account calculations. Once collect()
          // is completed, the instance will contain the measurements of the last hour
          // collected.
          host.getMeasurements().clear();
          thisHoursInstances.put(instanceId, host);
          accountServiceInventory.getServiceInstances().put(instanceId, host);

          events.forEach(event -> updateInstanceFromEvent(event, host, serviceTypeMeta));
        });

    return tallyCurrentAccountState(accountServiceInventory.getAccountNumber(), thisHoursInstances);
  }

  private AccountUsageCalculation tallyCurrentAccountState(
      String accountNumber, Map<String, Host> thisHoursInstances) {
    if (thisHoursInstances.isEmpty()) {
      return null;
    }
    AccountUsageCalculation accountUsageCalculation = new AccountUsageCalculation(accountNumber);
    thisHoursInstances
        .values()
        .forEach(
            instance ->
                instance
                    .getBuckets()
                    .forEach(
                        bucket -> {
                          UsageCalculation.Key usageKey =
                              new UsageCalculation.Key(
                                  bucket.getKey().getProductId(),
                                  bucket.getKey().getSla(),
                                  bucket.getKey().getUsage(),
                                  bucket.getKey().getBillingProvider(),
                                  bucket.getKey().getBillingAccountId());
                          instance
                              .getMeasurements()
                              .forEach(
                                  (uom, value) ->
                                      accountUsageCalculation.addUsage(
                                          usageKey,
                                          getHardwareMeasurementType(instance),
                                          uom,
                                          value));
                        }));
    return accountUsageCalculation;
  }

  private void updateInstanceFromEvent(
      Event event, Host instance, Optional<TagMetaData> serviceTypeMeta) {
    instance.setAccountNumber(event.getAccountNumber());
    instance.setInstanceType(event.getServiceType());
    instance.setInstanceId(event.getInstanceId());
    Optional.ofNullable(event.getBillingAccountId())
            .map(Optional::get)
            .ifPresent(instance::setBillingAccountId);
    Optional.ofNullable(event.getBillingProvider())
            .map(this::getBillingProvider)
            .ifPresent(instance::setBillingProvider);
    Optional.ofNullable(event.getCloudProvider())
        .map(this::getCloudProvider)
        .map(HardwareMeasurementType::toString)
        .ifPresent(instance::setCloudProvider);
    Optional.ofNullable(event.getHardwareType())
        .map(this::getHostHardwareType)
        .ifPresent(instance::setHardwareType);
    instance.setDisplayName(
        Optional.ofNullable(event.getDisplayName())
            .map(Optional::get)
            .orElse(event.getInstanceId()));
    instance.setLastSeen(event.getTimestamp());
    instance.setGuest(instance.getHardwareType() == HostHardwareType.VIRTUALIZED);
    Optional.ofNullable(event.getInventoryId())
        .map(Optional::get)
        .ifPresent(instance::setInventoryId);
    Optional.ofNullable(event.getHypervisorUuid())
        .map(Optional::get)
        .ifPresent(instance::setHypervisorUuid);
    Optional.ofNullable(event.getSubscriptionManagerId())
        .map(Optional::get)
        .ifPresent(instance::setSubscriptionManagerId);
    Optional.ofNullable(event.getMeasurements())
        .orElse(Collections.emptyList())
        .forEach(
            measurement -> {
              instance.setMeasurement(measurement.getUom(), measurement.getValue());
              instance.addToMonthlyTotal(
                  event.getTimestamp(), measurement.getUom(), measurement.getValue());
            });
    addBucketsFromEvent(instance, event, serviceTypeMeta);
  }

  private HostHardwareType getHostHardwareType(Event.HardwareType hardwareType) {
    switch (hardwareType) {
      case __EMPTY__:
        return null;
      case PHYSICAL:
        return HostHardwareType.PHYSICAL;
      case VIRTUAL:
        return HostHardwareType.VIRTUALIZED;
      case CLOUD:
        return HostHardwareType.CLOUD;
      default:
        throw new IllegalArgumentException(
            String.format("Unsupported hardware type: %s", hardwareType));
    }
  }

  private HardwareMeasurementType getHardwareMeasurementType(Host instance) {
    if (instance.getHardwareType() == null) {
      return HardwareMeasurementType.PHYSICAL;
    }
    switch (instance.getHardwareType()) {
      case CLOUD:
        return getCloudProvider(instance);
      case VIRTUALIZED:
        return HardwareMeasurementType.VIRTUAL;
      case PHYSICAL:
        return HardwareMeasurementType.PHYSICAL;
      default:
        throw new IllegalArgumentException(
            String.format("Unsupported hardware type: %s", instance.getHardwareType()));
    }
  }

  private HardwareMeasurementType getCloudProvider(Host instance) {
    if (instance.getCloudProvider() == null) {
      throw new IllegalArgumentException("Hardware type cloud, but no cloud provider specified");
    }
    return HardwareMeasurementType.valueOf(instance.getCloudProvider());
  }

  private HardwareMeasurementType getCloudProvider(Event.CloudProvider cloudProvider) {
    switch (cloudProvider) {
      case __EMPTY__:
        return null;
      case AWS:
        return HardwareMeasurementType.AWS;
      case AZURE:
        return HardwareMeasurementType.AZURE;
      case ALIBABA:
        return HardwareMeasurementType.ALIBABA;
      case GOOGLE:
        return HardwareMeasurementType.GOOGLE;
      default:
        throw new IllegalArgumentException(
            String.format("Unsupported value for cloud provider: %s", cloudProvider.value()));
    }
  }
  private BillingProvider getBillingProvider(Event.BillingProvider billingProvider) {
    switch (billingProvider) {
      case __EMPTY__:
        return null;
      case AWS:
        return BillingProvider.AWS;
      case AZURE:
        return BillingProvider.AZURE;
      case ORACLE:
        return BillingProvider.ORACLE;
      case GCP:
        return BillingProvider.GCP;
      case RED_HAT:
        return BillingProvider.RED_HAT;
      default:
        throw new IllegalArgumentException(
                String.format("Unsupported value for billing provider: %s", billingProvider.value()));
    }
  }

  private void addBucketsFromEvent(Host host, Event event, Optional<TagMetaData> serviceTypeMeta) {
    ServiceLevel effectiveSla =
        Optional.ofNullable(event.getSla())
            .map(Event.Sla::toString)
            .map(ServiceLevel::fromString)
            .orElse(serviceTypeMeta.map(TagMetaData::getDefaultSla).orElse(ServiceLevel.EMPTY));
    Usage effectiveUsage =
        Optional.ofNullable(event.getUsage())
            .map(Event.Usage::toString)
            .map(Usage::fromString)
            .orElse(serviceTypeMeta.map(TagMetaData::getDefaultUsage).orElse(Usage.EMPTY));
    BillingProvider effectiveProvider =
        Optional.ofNullable(event.getBillingProvider())
                .map(Event.BillingProvider::toString)
                .map(BillingProvider::fromString)
                .orElse(serviceTypeMeta.map(TagMetaData::getDefaultProvider).orElse(BillingProvider._ANY));
    String billingAcctId =
        Optional.ofNullable(event.getBillingAccountId())
                .map(Optional::get)
                .orElse(serviceTypeMeta.map(TagMetaData::getBillingAccountId).orElse(null));
    Set<String> productIds = getProductIds(event);

    for (String productId : productIds) {
      for (ServiceLevel sla : Set.of(effectiveSla, ServiceLevel._ANY)) {
        for (Usage usage : Set.of(effectiveUsage, Usage._ANY)) {
          for(BillingProvider billingProvider : Set.of(effectiveProvider, BillingProvider._ANY)) {
            HostTallyBucket bucket = new HostTallyBucket();
            bucket.setKey(new HostBucketKey(host, productId, sla, usage, billingProvider, billingAcctId, false));
            host.addBucket(bucket);
          }
        }
      }
    }
  }

  private Set<String> getProductIds(Event event) {
    Set<String> productIds = new HashSet<>();
    productIds.addAll(tagProfile.getTagsByRole(event.getRole()));

    Optional.ofNullable(event.getProductIds()).orElse(Collections.emptyList()).stream()
        .map(tagProfile::getTagsByEngProduct)
        .filter(Objects::nonNull)
        .forEach(productIds::addAll);

    return productIds;
  }

  @AllArgsConstructor
  @Getter
  public class CollectionResult {
    private DateRange range;
    private Map<OffsetDateTime, AccountUsageCalculation> calculations;
    private boolean wasRecalculated;
  }
}
