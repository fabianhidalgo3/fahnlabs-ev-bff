package com.emob.bff.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Validated request bodies of the front API. Field names match the motor domain API. */
public final class Requests {

	private Requests() {}

	/** Vehicle registration (snake_case, as stored by the motor). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record VehicleRequest(
		@NotBlank @Size(max = 80) String name,
		@Size(max = 40) String make,
		@Size(max = 40) String model,
		@Pattern(regexp = "^$|^[A-HJ-NPR-Z0-9]{17}$", message = "must be 17 characters without I, O or Q") String vin,
		@Pattern(regexp = "^$|^[0-9A-Fa-f:]{12,17}$", message = "must be a MAC address (12 hex digits)") String evccid,
		@Size(max = 20) String id_tag,
		@DecimalMin("1") @DecimalMax("300") Double battery_kwh,
		@DecimalMin("1") @DecimalMax("350") Double max_ac_kw,
		@Min(1) @Max(3) Integer phases,
		@Min(10) @Max(100) Integer target_soc,
		@Min(1) @Max(10) Integer priority) {}

	/** Charger pre-registration or update. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ChargerRequest(
		@Pattern(regexp = "^[\\w.-]{1,48}$", message = "letters, digits, dot, dash or underscore (max 48)") String id,
		@Size(max = 120) String location,
		@DecimalMin("1") @DecimalMax("400") Double maxPowerKw,
		@Min(1) @Max(10) Integer priority) {}

	/** Charging plan: start now (startAt null) or scheduled. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ChargingPlanRequest(
		@NotBlank String chargerId,
		@Min(1) @Max(10) Integer connectorId,
		Long vehicleId,
		@Size(max = 20) String idTag,
		Long startAt,
		Long departureAt,
		@Min(10) @Max(100) Integer targetSoc,
		@Positive Double maxKw) {}

	/** Remote start / stop / reset parameters. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CommandRequest(
		@Min(1) @Max(10) Integer connectorId,
		@Size(max = 20) String idTag,
		Long vehicleId,
		@Pattern(regexp = "Soft|Hard") String type) {}

	/** One message of the assistant conversation. */
	public record ChatMessage(@Pattern(regexp = "user|assistant") String role, @NotBlank @Size(max = 8000) String content) {}

	public record AssistantRequest(@Size(min = 1, max = 40) java.util.List<@jakarta.validation.Valid ChatMessage> messages, @Pattern(regexp = "en|es") String language) {}
}
