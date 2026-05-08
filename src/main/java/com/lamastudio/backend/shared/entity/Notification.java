package com.lamastudio.backend.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

import com.lamastudio.backend.shared.entity.DomainEnums.*;


@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification extends BaseEntity {

  @Column(nullable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  private NotificationType type;

  private String title;

  @Column(columnDefinition = "TEXT")
  private String message;

  private Boolean read = false;
}
