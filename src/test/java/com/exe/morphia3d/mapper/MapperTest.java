package com.exe.morphia3d.mapper;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.dto.response.OrderItemResponse;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.entity.ModelPart;
import com.exe.morphia3d.entity.OrderItem;
import com.exe.morphia3d.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
class MapperTest {

    @Autowired private ModelMapper modelMapper;
    @Autowired private OrderItemMapper orderItemMapper;

    @Test
    void modelResponseContainsUserImagesAndParts() {
        User owner = User.builder().id(42).name("Owner").email("owner@example.com").password("encoded").build();
        Model3d model = Model3d.builder().id(7).user(owner).name("Mapped").build();
        model.getImages().add(ModelImage.builder()
                .id(8)
                .model(model)
                .imagePath("preview")
                .isPrimary(true)
                .build());
        model.getParts().add(ModelPart.builder()
                .id(9)
                .model(model)
                .partName("Body")
                .partKey("body")
                .build());

        ModelResponse response = modelMapper.toResponse(model);

        assertThat(response.getUserId()).isEqualTo(42);
        assertThat(response.getModelImages()).hasSize(1);
        assertThat(response.getModelImages().get(0).getImagePath()).isEqualTo("preview");
        assertThat(response.getModelParts()).hasSize(1);
        assertThat(response.getModelParts().get(0).getPartName()).isEqualTo("Body");
    }

    @Test
    void orderItemResponseContainsModelId() {
        OrderItem item = OrderItem.builder()
                .id(3)
                .model(Model3d.builder().id(17).build())
                .quantity(2)
                .priceSnapshot(50.0)
                .build();

        OrderItemResponse response = orderItemMapper.toResponse(item);

        assertThat(response.getModelId()).isEqualTo(17);
    }
}
