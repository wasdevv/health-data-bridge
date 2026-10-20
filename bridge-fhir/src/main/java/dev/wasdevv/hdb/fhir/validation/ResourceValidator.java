package dev.wasdevv.hdb.fhir.validation;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.ValidationResult;
import dev.wasdevv.hdb.core.fhir.Systems;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.StructureDefinition;

/**
 * HAPI's instance validator with the base R4 definitions plus the bridge's own profile. Building the
 * chain loads the whole R4 spec, so there is one instance per application, warmed up at startup.
 */
public class ResourceValidator {

    private final FhirContext ctx;
    private final FhirValidator validator;
    private final StructureDefinition billableEncounter;

    public ResourceValidator(FhirContext ctx) {
        this.ctx = ctx;
        this.billableEncounter = loadProfile(ctx, "/profiles/billable-encounter.json");
        PrePopulatedValidationSupport ours = new PrePopulatedValidationSupport(ctx);
        ours.addStructureDefinition(billableEncounter);
        ValidationSupportChain chain = new ValidationSupportChain(
                new DefaultProfileValidationSupport(ctx),
                ours,
                new SnapshotGeneratingValidationSupport(ctx),
                new InMemoryTerminologyServerValidationSupport(ctx),
                new CommonCodeSystemsTerminologyService(ctx));
        FhirInstanceValidator instanceValidator = new FhirInstanceValidator(chain);
        this.validator = ctx.newValidator().registerValidatorModule(instanceValidator);
        warmUp();
    }

    /** Base R4 rules plus any profile the resource declares in {@code meta.profile}. */
    public void validateOrThrow(IBaseResource resource) {
        ValidationResult result = validator.validateWithResult(resource);
        if (!result.isSuccessful()) {
            throw new UnprocessableEntityException(ctx, result.toOperationOutcome());
        }
    }

    /** Forces a profile the resource does not declare: the billing check runs this on every finished Encounter. */
    public ValidationResult validateAgainst(IBaseResource resource, String profileUrl) {
        return validator.validateWithResult(resource, new ValidationOptions().addProfile(profileUrl));
    }

    public static List<String> errors(ValidationResult result) {
        return result.getMessages().stream()
                .filter(m -> m.getSeverity().ordinal() >= ResultSeverityEnum.ERROR.ordinal())
                .map(m -> m.getLocationString() + ": " + m.getMessage())
                .toList();
    }

    public StructureDefinition billableEncounterProfile() {
        return billableEncounter;
    }

    private void warmUp() {
        Encounter e = new Encounter();
        e.setStatus(Encounter.EncounterStatus.PLANNED);
        e.getClass_().setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode").setCode("AMB");
        validator.validateWithResult(e);
        validateAgainst(e, Systems.BILLABLE_ENCOUNTER_PROFILE);
    }

    private static StructureDefinition loadProfile(FhirContext ctx, String path) {
        try (InputStream in = ResourceValidator.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("profile not on classpath: " + path);
            }
            return ctx.newJsonParser().parseResource(StructureDefinition.class, in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
