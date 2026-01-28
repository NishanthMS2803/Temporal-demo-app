package com.example.subscription.activity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

public class PaymentActivityImpl implements PaymentActivity {

    private static final Logger logger = LoggerFactory.getLogger(PaymentActivityImpl.class);
    private static final String API_URL = "http://localhost:8081";

    @Override
    public void charge(String subscriptionId) {
        String transactionId = UUID.randomUUID().toString().substring(0, 8);

        logger.info("[TXN:{}] Starting payment for subscription: {}",
                    transactionId, subscriptionId);

        // Check if gateway down simulation is enabled (by querying API)
        if (isGatewayDown(transactionId)) {
            logger.error("[TXN:{}] ⚠️  Payment gateway is temporarily unavailable (simulated)", transactionId);
            throw new RuntimeException("Payment gateway temporarily unavailable");
        }

        try {
            // STEP 1: Check balance via API (simulating external payment service call)
            logger.info("[TXN:{}] Checking balance for subscription {}...", transactionId, subscriptionId);

            URL checkUrl = new URL(API_URL + "/wallet/" + subscriptionId);
            HttpURLConnection checkConn = (HttpURLConnection) checkUrl.openConnection();
            checkConn.setRequestMethod("GET");

            int checkResponseCode = checkConn.getResponseCode();
            if (checkResponseCode != 200) {
                logger.error("[TXN:{}] Failed to check balance: HTTP {}", transactionId, checkResponseCode);
                throw new RuntimeException("Failed to check balance: HTTP " + checkResponseCode);
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(checkConn.getInputStream()));
            String inputLine;
            StringBuilder response = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            // Parse balance from JSON response (simple parsing)
            String jsonResponse = response.toString();
            double balance = parseBalance(jsonResponse);
            double price = parsePrice(jsonResponse);

            logger.info("[TXN:{}] Balance: ${}, Required: ${}", transactionId, balance, price);

            if (balance < price) {
                logger.error("[TXN:{}] Insufficient funds - Balance: ${}, Required: ${}",
                            transactionId, balance, price);
                throw new RuntimeException("Insufficient funds - Balance: $" + balance + ", Required: $" + price);
            }

            // STEP 2: Charge via API (simulating actual payment)
            logger.info("[TXN:{}] Sufficient balance found. Processing charge...", transactionId);

            URL chargeUrl = new URL(API_URL + "/wallet/" + subscriptionId + "/charge");
            HttpURLConnection chargeConn = (HttpURLConnection) chargeUrl.openConnection();
            chargeConn.setRequestMethod("POST");
            chargeConn.setDoOutput(true);

            int chargeResponseCode = chargeConn.getResponseCode();
            if (chargeResponseCode != 200) {
                logger.error("[TXN:{}] Payment processing failed: HTTP {}", transactionId, chargeResponseCode);
                throw new RuntimeException("Payment processing failed: HTTP " + chargeResponseCode);
            }

            // Read new balance
            BufferedReader chargeIn = new BufferedReader(new InputStreamReader(chargeConn.getInputStream()));
            StringBuilder chargeResponse = new StringBuilder();
            while ((inputLine = chargeIn.readLine()) != null) {
                chargeResponse.append(inputLine);
            }
            chargeIn.close();

            double newBalance = parseBalance(chargeResponse.toString());
            logger.info("[TXN:{}] ✓ Payment SUCCESS - Charged ${}. New balance: ${}",
                       transactionId, price, newBalance);

        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            logger.error("[TXN:{}] Payment failed with exception: {}", transactionId, e.getMessage());
            throw new RuntimeException("Payment failed: " + e.getMessage(), e);
        }
    }

    // Simple JSON parsing (just extract numbers after "balance": and "subscriptionPrice":)
    private double parseBalance(String json) {
        try {
            // Try "balance" first
            if (json.contains("\"balance\":")) {
                String balanceStr = json.split("\"balance\":")[1].split(",")[0].split("}")[0].trim();
                return Double.parseDouble(balanceStr);
            }
            // Try "newBalance" (from charge response)
            if (json.contains("\"newBalance\":")) {
                String balanceStr = json.split("\"newBalance\":")[1].split(",")[0].split("}")[0].trim();
                return Double.parseDouble(balanceStr);
            }
            logger.error("Could not find balance or newBalance in JSON: {}", json);
            return 0.0;
        } catch (Exception e) {
            logger.error("Failed to parse balance from JSON: {}", json, e);
            return 0.0;
        }
    }

    private double parsePrice(String json) {
        try {
            if (json.contains("\"subscriptionPrice\":")) {
                String priceStr = json.split("\"subscriptionPrice\":")[1].split(",")[0].split("}")[0].trim();
                return Double.parseDouble(priceStr);
            }
            // Default if not found
            return 10.0;
        } catch (Exception e) {
            logger.error("Failed to parse price from JSON: {}", json, e);
            return 10.0; // Default price
        }
    }

    /**
     * Check if gateway down simulation is enabled by querying the API.
     * This allows the API and Worker (separate JVMs) to share the simulation state.
     */
    private boolean isGatewayDown(String transactionId) {
        try {
            URL statusUrl = new URL(API_URL + "/simulate/gateway-down/status");
            HttpURLConnection conn = (HttpURLConnection) statusUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000); // 1 second timeout
            conn.setReadTimeout(1000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();

                // Parse JSON: {"gatewayDown":true} or {"gatewayDown":false}
                String json = response.toString();
                boolean isDown = json.contains("\"gatewayDown\":true");

                if (isDown) {
                    logger.debug("[TXN:{}] Gateway status check: DOWN", transactionId);
                } else {
                    logger.debug("[TXN:{}] Gateway status check: UP", transactionId);
                }

                return isDown;
            } else {
                logger.warn("[TXN:{}] Failed to check gateway status (HTTP {}), assuming UP",
                           transactionId, responseCode);
                return false; // Assume gateway is up if check fails
            }
        } catch (Exception e) {
            logger.warn("[TXN:{}] Exception checking gateway status: {}, assuming UP",
                       transactionId, e.getMessage());
            return false; // Assume gateway is up if check fails
        }
    }
}
