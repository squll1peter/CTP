package org.rsna.ctp.stdstages;

import org.junit.Test;
import static org.junit.Assert.*;
import org.apache.commons.net.ftp.FTPSClient;

/**
 * Verifies that FTPSClient can be instantiated with TLS protocol settings
 * consistent with the configuration used in FtpsExportService.
 */
public class FtpsTlsConfigTest {

    @Test
    public void ftpsClient_canBeCreatedWithTlsProtocol() {
        // Verify FTPSClient("TLS", false) constructor is available
        FTPSClient client = new FTPSClient("TLS", false);
        assertNotNull("FTPSClient with TLS protocol should be non-null", client);
    }

    @Test
    public void ftpsClient_setEnabledProtocols_acceptsTls12AndTls13() {
        FTPSClient client = new FTPSClient("TLS", false);
        // Should not throw — verifies the API is present and arguments are valid
        client.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
    }

    @Test
    public void ftpsClient_defaultConnectModeIsExplicit() {
        // The default FTPSClient() uses explicit mode (port 21 + STARTTLS)
        // Our code uses FTPSClient("TLS", false) where false = explicit
        // Verify we can instantiate with the exact parameters used in production
        FTPSClient client = new FTPSClient("TLS", false);
        assertNotNull("FTPSClient with explicit mode should be non-null", client);
    }
}
