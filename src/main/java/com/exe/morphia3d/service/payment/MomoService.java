package com.exe.morphia3d.service.payment;

import com.exe.morphia3d.config.MomoConfig;
import com.exe.morphia3d.utils.MomoUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service("momoService")
@RequiredArgsConstructor
public class MomoService implements PaymentGatewayService {

    private final MomoConfig momoConfig;
    private final MomoUtil momoUtil;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String createPaymentUrl(Integer orderId, Double amount, String ipAddress) {
        String requestId = UUID.randomUUID().toString();
        // Gắn orderId thật vào momoOrderId để lúc IPN/return map lại đúng đơn hàng
        String momoOrderId = orderId + "_" + System.currentTimeMillis();
        String orderInfo = "Thanh toan don hang " + orderId;
        String extraData = "";
        long amountLong = (long) Math.round(amount);

        // Raw signature theo đúng thứ tự MoMo yêu cầu (KHÔNG được tự ý sắp xếp lại alphabet như VNPay)
        String rawSignature = "accessKey=" + momoConfig.getAccessKey() +
                "&amount=" + amountLong +
                "&extraData=" + extraData +
                "&ipnUrl=" + momoConfig.getIpnUrl() +
                "&orderId=" + momoOrderId +
                "&orderInfo=" + orderInfo +
                "&partnerCode=" + momoConfig.getPartnerCode() +
                "&redirectUrl=" + momoConfig.getRedirectUrl() +
                "&requestId=" + requestId +
                "&requestType=" + momoConfig.getRequestType();

        String signature = momoUtil.hmacSHA256(momoConfig.getSecretKey(), rawSignature);

        Map<String, Object> body = new HashMap<>();
        body.put("partnerCode", momoConfig.getPartnerCode());
        body.put("accessKey", momoConfig.getAccessKey());
        body.put("requestId", requestId);
        body.put("amount", String.valueOf(amountLong));
        body.put("orderId", momoOrderId);
        body.put("orderInfo", orderInfo);
        body.put("redirectUrl", momoConfig.getRedirectUrl());
        body.put("ipnUrl", momoConfig.getIpnUrl());
        body.put("extraData", extraData);
        body.put("requestType", momoConfig.getRequestType());
        body.put("signature", signature);
        body.put("lang", "vi");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        try {
            String response = restTemplate.postForObject(momoConfig.getEndpoint(), requestEntity, String.class);
            JsonNode json = objectMapper.readTree(response);

            int resultCode = json.path("resultCode").asInt(-1);
            if (resultCode != 0) {
                String message = json.path("message").asText("Không rõ lỗi");
                throw new RuntimeException("MoMo trả lỗi: " + message + " (resultCode=" + resultCode + ")");
            }

            return json.path("payUrl").asText();
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi gọi API tạo thanh toán MoMo: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean verifyReturnUrl(Map<String, String> params) {
        String receivedSignature = params.get("signature");

        // Thứ tự field cố định theo tài liệu MoMo cho redirect/IPN response
        String rawSignature = "accessKey=" + momoConfig.getAccessKey() +
                "&amount=" + params.get("amount") +
                "&extraData=" + params.getOrDefault("extraData", "") +
                "&message=" + params.get("message") +
                "&orderId=" + params.get("orderId") +
                "&orderInfo=" + params.get("orderInfo") +
                "&orderType=" + params.get("orderType") +
                "&partnerCode=" + params.get("partnerCode") +
                "&payType=" + params.get("payType") +
                "&requestId=" + params.get("requestId") +
                "&responseTime=" + params.get("responseTime") +
                "&resultCode=" + params.get("resultCode") +
                "&transId=" + params.get("transId");

        String calculatedSignature = momoUtil.hmacSHA256(momoConfig.getSecretKey(), rawSignature);
        return calculatedSignature.equals(receivedSignature);
    }
}