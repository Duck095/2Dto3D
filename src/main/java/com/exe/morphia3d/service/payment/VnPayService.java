package com.exe.morphia3d.service.payment;

import com.exe.morphia3d.config.VnPayConfig;
import com.exe.morphia3d.utils.VnPayUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service("vnPayService")
@RequiredArgsConstructor
public class VnPayService implements PaymentGatewayService {

    private final VnPayConfig vnPayConfig;
    private final VnPayUtil vnPayUtil;

    @Override
    public String createPaymentUrl(Integer orderId, Double amount, String ipAddress) {
        String txnRef = vnPayUtil.getRandomTxnRef();
        String createDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String expireDate = LocalDateTime.now().plusMinutes(15)
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

        Map<String, String> params = new HashMap<>();
        params.put("vnp_Version", vnPayConfig.getVersion());
        params.put("vnp_Command", vnPayConfig.getCommand());
        params.put("vnp_TmnCode", vnPayConfig.getTmnCode());
        // VNPay yêu cầu nhân 100 để bỏ phần thập phân
        params.put("vnp_Amount", String.valueOf((long) (amount * 100)));
        params.put("vnp_CurrCode", vnPayConfig.getCurrCode());
        params.put("vnp_TxnRef", orderId + "_" + txnRef); // gắn orderId để return về match lại đơn hàng
        params.put("vnp_OrderInfo", "Thanh toan don hang " + orderId);
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", vnPayConfig.getLocale());
        params.put("vnp_ReturnUrl", vnPayConfig.getReturnUrl());
        params.put("vnp_IpAddr", ipAddress);
        params.put("vnp_CreateDate", createDate);
        params.put("vnp_ExpireDate", expireDate);

        String hashData = vnPayUtil.buildHashData(params);
        String secureHash = vnPayUtil.hmacSHA512(vnPayConfig.getHashSecret(), hashData);

        StringBuilder query = new StringBuilder();
        params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                            .append('=')
                            .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                            .append('&');
                });
        query.append("vnp_SecureHash=").append(secureHash);

        return vnPayConfig.getPayUrl() + "?" + query;
    }

    @Override
    public boolean verifyReturnUrl(Map<String, String> params) {
        Map<String, String> fields = new HashMap<>(params);
        String receivedHash = fields.remove("vnp_SecureHash");
        fields.remove("vnp_SecureHashType");

        String hashData = vnPayUtil.buildHashData(fields);
        String calculatedHash = vnPayUtil.hmacSHA512(vnPayConfig.getHashSecret(), hashData);

        return calculatedHash.equals(receivedHash);
    }
}