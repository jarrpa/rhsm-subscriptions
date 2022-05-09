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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import javax.ws.rs.BadRequestException;
import org.candlepin.subscriptions.FixedClockConfiguration;
import org.candlepin.subscriptions.db.model.config.OptInType;
import org.candlepin.subscriptions.security.OptInController;
import org.candlepin.subscriptions.security.WithMockRedHatPrincipal;
import org.candlepin.subscriptions.tally.files.ReportingAccountWhitelist;
import org.candlepin.subscriptions.util.ApplicationClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"api", "test"})
@WithMockRedHatPrincipal("123456")
class OptInResourceTest {

  private ApplicationClock clock;

  @MockBean private ReportingAccountWhitelist accountWhitelist;

  @MockBean private OptInController controller;

  @Autowired private OptInResource resource;

  @BeforeEach
  public void setupTests() throws IOException {
    this.clock = new FixedClockConfiguration().fixedClock();
    when(accountWhitelist.hasAccount("account123456")).thenReturn(true);
  }

  @Test
  void testDeleteOptInConfig() {
    resource.deleteOptInConfig();
    Mockito.verify(controller).optOut("account123456", "owner123456");
  }

  @Test
  void testGet() {
    resource.getOptInConfig();
    Mockito.verify(controller).getOptInConfig("account123456", "owner123456");
  }

  @Test
  void testPut() {
    resource.putOptInConfig(false, false, false);
    Mockito.verify(controller)
        .optIn(
            "account123456",
            "owner123456",
            OptInType.API,
            Boolean.FALSE,
            Boolean.FALSE,
            Boolean.FALSE);
  }

  @Test
  void testPutDefaultsToTrue() {
    resource.putOptInConfig(null, null, null);
    Mockito.verify(controller)
        .optIn(
            "account123456",
            "owner123456",
            OptInType.API,
            Boolean.TRUE,
            Boolean.TRUE,
            Boolean.TRUE);
  }

  @Test
  @WithMockRedHatPrincipal(value = "123456", nullifyOwner = true)
  void testMissingOrgOnDelete() {
    assertThrows(BadRequestException.class, () -> resource.deleteOptInConfig());
  }

  @Test
  @WithMockRedHatPrincipal(value = "123456", nullifyAccount = true)
  void testMissingAccountOnDelete() {
    assertThrows(BadRequestException.class, () -> resource.deleteOptInConfig());
  }

  @Test
  @WithMockRedHatPrincipal(
      value = "123456",
      roles = {})
  void testAccessDeniedForDeleteAccountConfigWhenUserIsNotAnAdmin() {
    assertThrows(AccessDeniedException.class, () -> resource.deleteOptInConfig());
  }

  @Test
  @WithMockRedHatPrincipal(value = "123456", nullifyOwner = true)
  void testMissingOrgOnGet() {
    assertThrows(BadRequestException.class, () -> resource.getOptInConfig());
  }

  @Test
  @WithMockRedHatPrincipal(value = "123456", nullifyAccount = true)
  void testMissingAccountOnGet() {
    assertThrows(BadRequestException.class, () -> resource.getOptInConfig());
  }

  @Test
  @WithMockRedHatPrincipal(
      value = "123456",
      roles = {})
  void testAccessDeniedForGetAccountConfigWhenUserIsNotAnAdmin() {
    assertThrows(AccessDeniedException.class, () -> resource.getOptInConfig());
  }

  @Test
  @WithMockRedHatPrincipal(value = "123456", nullifyOwner = true)
  void testMissingOrgOnPut() {
    assertThrows(BadRequestException.class, () -> resource.putOptInConfig(true, true, true));
  }

  @Test
  @WithMockRedHatPrincipal(value = "123456", nullifyAccount = true)
  void testMissingAccountOnPut() {
    assertThrows(BadRequestException.class, () -> resource.putOptInConfig(true, true, true));
  }

  @Test
  @WithMockRedHatPrincipal(
      value = "123456",
      roles = {})
  void testAccessDeniedForOptInWhenUserIsNotAnAdmin() {
    assertThrows(AccessDeniedException.class, () -> resource.putOptInConfig(true, true, true));
  }
}
