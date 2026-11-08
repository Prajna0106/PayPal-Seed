
# Getting Started with PayPal Seed Partner API

## Introduction

PayPal Seed is savings and fair microloans built into PayPal. This is the **partner** slice of the Seed API:
the three routes that need no sign-in.

- **Verify a credit record.** A member shares a link from their Seed app. A partner lender reads the token in
  that link and asks Seed whether it is genuine, current and what it says.
- **Read the safety-fund summary.** Public totals for the community safety fund.
- **Start a donation.** Get the `customId` to put on a PayPal order or Donate button.

The sandbox server holds demo data only. No real money moves.

### Conventions

- Money is integer cents in USD. `125000` means $1,250.00.
- Dates are business dates in America/Los_Angeles, written `YYYY-MM-DD`. Timestamps are UTC (RFC 3339).
- Errors are RFC 9457 `application/problem+json` with a stable `type` (`https://problems.paypal-seed.dev/{code}`)
  and the same `code` property. Branch on `code`, not on the message.
- The responses never contain a name, email, payer id, loan amount or member id.

### Rate limit

Each client address may make a limited number of requests per minute across the three routes (ADR 0030).
Past the limit the API answers `429` with a `Retry-After` header in seconds. Wait that long, then retry.

## Building

### Requirements

The SDK relies on **Node.js** and **npm** (to resolve dependencies). It also requires **Typescript version >=4.1**. You can download and install Node.js and [npm](https://www.npmjs.com/) from [the official Node.js website](https://nodejs.org/en/download/).

> **NOTE:** npm is installed by default when Node.js is installed.

### Verify Successful Installation

Run the following commands in the command prompt or shell of your choice to check if Node.js and npm are successfully installed:

* Node.js: `node --version`

* npm: `npm --version`

![Version Check](https://apidocs.io/illustration/typescript?workspaceFolder=PayPalSeedPartnerAPI&step=versionCheck)

### Install Dependencies

- To resolve all dependencies, go to the **SDK root directory** and run the following command with npm:

```bash
npm install
```

- This will install all dependencies in the **node_modules** folder.

![Resolve Dependencies](https://apidocs.io/illustration/typescript?workspaceFolder=PayPalSeedPartnerAPI&workspaceName=paypal-seed-partner-apilib&step=resolveDependency)

## Installation

The following section explains how to use the generated library in a new project.

### 1. Initialize the Node Project

- Open an IDE/text editor for JavaScript like Visual Studio Code. The basic workflow presented here is also applicable if you prefer using a different editor or IDE.

- Click on **File** and select **Open Folder**. Select an empty folder of your project, the folder will become visible in the sidebar on the left.

![Open Folder](https://apidocs.io/illustration/typescript?step=openProject)

- To initialize the Node project, click on **Terminal** and select **New Terminal**. Execute the following command in the terminal:

```bash
npm init --y
```

![Initialize the Node Project](https://apidocs.io/illustration/typescript?step=initializeProject)

### 2. Add Dependencies to the Client Library

- The created project manages its dependencies using its `package.json` file. In order to add a dependency on the *PayPal Seed Partner APILib* client library, double click on the `package.json` file in the bar on the left and add the dependency to the package in it.

![Add PaypalSeedPartnerApilib Dependency](https://apidocs.io/illustration/typescript?workspaceFolder=PayPalSeedPartnerAPI&workspaceName=paypal-seed-partner-apilib&step=importDependency)

- To install the package in the project, run the following command in the terminal:

```bash
npm install
```

![Install PaypalSeedPartnerApilib Dependency](https://apidocs.io/illustration/typescript?step=installDependency)

## Test the SDK

To validate the functionality of this SDK, you can execute all tests located in the `test` directory. This SDK utilizes `Jest` as both the testing framework and test runner.

To run the tests, navigate to the root directory of the SDK and execute the following command:

```bash
npm run test
```

Or you can also run tests with coverage report:

```bash
npm run test:coverage
```

## Initialize the API Client

**_Note:_** Documentation for the client can be found [here.](doc/client.md)

The following parameters are configurable for the API Client:

| Parameter | Type | Description |
|  --- | --- | --- |
| environment | [`Environment`](README.md#environments) | The API environment. <br> **Default: `Environment.Production`** |
| timeout | `number` | Timeout for API calls.<br>*Default*: `0` |
| httpClientOptions | [`Partial<HttpClientOptions>`](doc/http-client-options.md) | Stable configurable http client options. |
| unstableHttpClientOptions | `any` | Unstable configurable http client options. |

The API client can be initialized as follows:

### Code-Based Client Initialization

```ts
import { Client, Environment } from 'paypal-seed-partner-apilib';

const client = new Client({
  timeout: 0,
  environment: Environment.Production,
});
```

### Configuration-Based Client Initialization

```ts
import * as path from 'path';
import * as fs from 'fs';
import { Client } from 'paypal-seed-partner-apilib';

// Provide absolute path for the configuration file
const absolutePath = path.resolve('./config.json');

// Read the configuration file content
const fileContent = fs.readFileSync(absolutePath, 'utf-8');

// Initialize client from JSON configuration content
const client = Client.fromJsonConfig(fileContent);
```

See the [Configuration-Based Client Initialization](doc/configuration-based-client-initialization.md) section for details.

### Environment-Based Client Initialization

```ts
import * as dotenv from 'dotenv';
import * as path from 'path';
import * as fs from 'fs';
import { Client } from 'paypal-seed-partner-apilib';

// Optional - Provide absolute path for the .env file
const absolutePath = path.resolve('./.env');

if (fs.existsSync(absolutePath)) {
  // Load environment variables from .env file
  dotenv.config({ path: absolutePath, override: true });
}

// Initialize client using environment variables
const client = Client.fromEnvironment(process.env);
```

See the [Environment-Based Client Initialization](doc/environment-based-client-initialization.md) section for details.

## Environments

The SDK can be configured to use a different environment for making API calls. Available environments are:

### Fields

| Name | Description |
|  --- | --- |
| Production | **Default** Sandbox (demo data, no real money). The free host may need up to a minute to wake after idle. |
| Environment2 | Local development |

## List of APIs

* [Record-Public](doc/controllers/record-public.md)
* [Reserve](doc/controllers/reserve.md)

## SDK Infrastructure

### Configuration

* [HttpClientOptions](doc/http-client-options.md)
* [RetryConfiguration](doc/retry-configuration.md)
* [ProxySettings](doc/proxy-settings.md)
* [Configuration-Based Client Initialization](doc/configuration-based-client-initialization.md)
* [Environment-Based Client Initialization](doc/environment-based-client-initialization.md)

### HTTP

* [HttpRequest](doc/http-request.md)

### Utilities

* [ApiResponse](doc/api-response.md)
* [ApiError](doc/api-error.md)

