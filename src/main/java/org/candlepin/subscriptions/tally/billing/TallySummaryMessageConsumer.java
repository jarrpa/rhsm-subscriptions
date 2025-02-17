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
package org.candlepin.subscriptions.tally.billing;

import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.candlepin.subscriptions.json.BillableUsage;
import org.candlepin.subscriptions.json.TallySummary;
import org.candlepin.subscriptions.task.TaskQueueProperties;
import org.candlepin.subscriptions.util.KafkaConsumerRegistry;
import org.candlepin.subscriptions.util.SeekableKafkaConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/*
 * Processes messages on the TallySummary topic and delegates to the BillingProducer for processing.
 */
@Service
@Slf4j
public class TallySummaryMessageConsumer extends SeekableKafkaConsumer {

  private BillingProducer billingProducer;

  @Autowired
  public TallySummaryMessageConsumer(
      BillingProducer billingProducer,
      @Qualifier("billingProducerTallySummaryTopicProperties")
          TaskQueueProperties tallySummaryTopicProperties,
      KafkaConsumerRegistry kafkaConsumerRegistry) {
    super(tallySummaryTopicProperties, kafkaConsumerRegistry);
    this.billingProducer = billingProducer;
  }

  @Timed("rhsm-subscriptions.billing-producer.tally-summary")
  @KafkaListener(
      id = "#{__listener.groupId}",
      topics = "#{__listener.topic}",
      containerFactory = "billingProducerKafkaTallySummaryListenerContainerFactory")
  public void receive(TallySummary tallySummary) {
    log.debug("Tally Summary recieved. Producing billable usage.}");
    BillableUsage usage =
        new BillableUsage()
            .withAccountNumber(tallySummary.getAccountNumber())
            .withBillableTallySnapshots(tallySummary.getTallySnapshots());
    this.billingProducer.produce(usage);
  }
}
