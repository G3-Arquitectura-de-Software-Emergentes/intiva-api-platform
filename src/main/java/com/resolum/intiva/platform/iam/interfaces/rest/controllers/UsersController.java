package com.resolum.intiva.platform.iam.interfaces.rest.controllers;

import com.resolum.intiva.platform.iam.domain.model.queries.GetUserByEmailQuery;
import com.resolum.intiva.platform.iam.domain.model.queries.GetUserByIdQuery;
import com.resolum.intiva.platform.iam.domain.model.valueobjects.Email;
import com.resolum.intiva.platform.iam.domain.services.UserQueryService;
import com.resolum.intiva.platform.iam.interfaces.rest.assemblers.UserResourceFromEntityAssembler;
import com.resolum.intiva.platform.iam.interfaces.rest.resources.requests.GetUserByEmailResource;
import com.resolum.intiva.platform.iam.interfaces.rest.resources.responses.UserResource;
import com.resolum.intiva.platform.shared.domain.valueobjects.UserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * UsersController is a REST controller that handles user-related endpoints, such as retrieving user details by ID or email. It defines the API endpoints for user retrieval operations and uses the UserQueryService to perform the necessary business logic for fetching user information.
 *
 * <p>
 *      This controller is responsible for handling user-query requests.
 *      It exposes two endpoints:
 *      <ul>
 *          <li>GET /api/v1/users/:id</li>
 *          <li>GET /api/v1/users</li>
 *      </ul>
 * </p>
 */
@RestController
@RequestMapping(value = "/api/v1/users", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Users", description = "Available User Endpoints")
public class UsersController {

    // UserQueryService is a service that handles user-related queries such as fetching user details by ID or email. It is injected into the controller to perform the necessary business logic for retrieving user information.
    private final UserQueryService userQueryService;

    // Constructor injection for the UserQueryService dependency
    public UsersController(UserQueryService userQueryService) {
        this.userQueryService = userQueryService;
    }

    @GetMapping("/me")
    public ResponseEntity<java.util.Map<String, Long>> currentUser(java.security.Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        return userQueryService.handle(new GetUserByEmailQuery(new Email(principal.getName())))
                .map(user -> ResponseEntity.ok(java.util.Map.of("id", user.getId())))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    /**
     * Endpoint to retrieve a user's details by their unique identifier (ID). It accepts the user ID as a path variable and returns the corresponding user information if found.
     * @param userId The unique identifier (ID) of the user to retrieve, provided as a path variable in the URL. This ID is used to query the user information from the system.
     * @return A ResponseEntity containing the UserResource if the user is found, or an appropriate error response if the user is not found or if the request is unauthorized.
     */
    @GetMapping(value = "/{userId}")
    @Operation(
            summary = "Get user by ID",
            description = "Endpoint to retrieve a user's details by their unique identifier (ID). It accepts the user ID as a path variable and returns the corresponding user information if found."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "User retrieved successfully.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UserResource.class),
                            examples = {
                                    @ExampleObject(
                                            name = "Successful Response",
                                            summary = "An example of a successful response",
                                            value = """
                                                    {
                                                        "userId": "6959b139b6c5058d7b5c2280",
                                                        "email": "nicolas@gmail.com"
                                                    }
                                                    """,
                                            description = "The response will contain the user details."
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "User not found."
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized."
            )
    })
    public ResponseEntity<UserResource> getUserById(
            @Parameter(
                    description = "The ID of the user to retrieve.",
                    example = "6959b139b6c5058d7b5c2280",
                    required = true
            )
            @PathVariable Long userId
    ) {
        var id = new UserId(userId);
        var getUserByIdQuery = new GetUserByIdQuery(id);
        var user = userQueryService.handle(getUserByIdQuery);
        if (user.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        var userResource = UserResourceFromEntityAssembler.toResourceFromEntity(user.get());
        return ResponseEntity.ok(userResource);
    }

    /**
     * Endpoint to retrieve a user's details by their email. It accepts the user email in the request body and returns the corresponding user information if found.
     * @param resource The GetUserByEmailResource object containing the email of the user to retrieve, sent in the request body.
     * @return A ResponseEntity containing the UserResource if the user is found, or an appropriate error response if the user is not found or if the request is unauthorized.
     */
    @GetMapping
    @Operation(summary = "Get user by email", description = "Get the user with the given email.")
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "User retrieved successfully.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UserResource.class),
                            examples = {
                                    @ExampleObject(
                                            name = "Successful Response",
                                            summary = "An example of a successful response",
                                            value = """
                                                    {
                                                        "id": "6959b139b6c5058d7b5c2280",
                                                        "email": "nicolas@gmail.com"
                                                    }
                                                    """,
                                            description = "A successful response containing the user details."
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "User with the given username was not found."
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized."
            )
    })
    public ResponseEntity<UserResource> getUserByEmail(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "A request resource containing the username of the user to retrieve.",
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UserResource.class),
                            examples = {
                                    @ExampleObject(
                                            name = "Good fetch",
                                            summary = "A valid username fetch example",
                                            value = """
                                                    {
                                                        "email": "nicolas@gmail.com"
                                                    }
                                                    """,
                                            description = "The username (email) of an existing user in the system."
                                    ),
                                    @ExampleObject(
                                            name = "Bad fetch",
                                            summary = "An invalid username fetch example",
                                            value = """
                                                    {
                                                        "email": "hello_world"
                                                    }
                                                    """,
                                            description = "Username (email) has an invalid format so there won't be any user with that username."
                                    )
                            }
                    )
            )
            @RequestBody GetUserByEmailResource resource
    ) {
        var email = new Email(resource.email());
        var getUserByEmailQuery = new GetUserByEmailQuery(email);
        var user = userQueryService.handle(getUserByEmailQuery);
        if (user.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var userResource = UserResourceFromEntityAssembler.toResourceFromEntity(user.get());
        return ResponseEntity.ok(userResource);
    }
}
