package com.example.responder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class NetworkTest {
    public static void main(String[] args) {
        System.out.println(">>> 1. Starting Network Diagnostic...");

        // 1. Check if we can just resolve DNS
        try {
            System.out.println(">>> 2. Testing DNS resolution...");
            java.net.InetAddress address = java.net.InetAddress.getByName("api.anthropic.com");
            System.out.println("   Success: Resolved to " + address.getHostAddress());
        } catch (Exception e) {
            System.err.println("   FAILURE: DNS Resolution failed. " + e.getMessage());
            return;
        }

        // 2. Check strict HTTP connection
        try {
            System.out.println(">>> 3. Testing HTTPS Handshake (Java 21 Client)...");
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com")) // Base URL check
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("   Success: Connected! Status Code: " + response.statusCode());
        } catch (Exception e) {
            System.out.println("   FAILURE: Connection blocked.");
            e.printStackTrace(); // This will print the REAL error, not just "null"
        }
    }
}