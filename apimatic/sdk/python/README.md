
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

You must have Python `3.7+` installed on your system to install and run this SDK. This SDK package depends on other Python packages like pytest, etc. These dependencies are defined in the `requirements.txt` file that comes with the SDK. To resolve these dependencies, you can use the PIP Dependency manager. Install it by following steps at [https://pip.pypa.io/en/stable/installing/](https://pip.pypa.io/en/stable/installing/).

Python and PIP executables should be defined in your PATH. Open command prompt and type `pip --version`. This should display the version of the PIP Dependency Manager installed if your installation was successful and the paths are properly defined.

* Using command line, navigate to the directory containing the generated files (including `requirements.txt`) for the SDK.
* Run the command `pip install -r requirements.txt`. This should install all the required dependencies.

![Building SDK - Step 1](https://apidocs.io/illustration/python?workspaceFolder=Paypalseedpartnerapi-Python&step=installDependencies)

## Installation

The following section explains how to use the paypalseedpartnerapi library in a new project.

### 1. Open Project in an IDE

Open up a Python IDE like PyCharm. The basic workflow presented here is also applicable if you prefer using a different editor or IDE.

![Open project in PyCharm - Step 1](https://apidocs.io/illustration/python?workspaceFolder=Paypalseedpartnerapi-Python&step=pyCharm)

Click on `Open` in PyCharm to browse to your generated SDK directory and then click `OK`.

![Open project in PyCharm - Step 2](https://apidocs.io/illustration/python?workspaceFolder=Paypalseedpartnerapi-Python&step=openProject0)

The project files will be displayed in the side bar as follows:

![Open project in PyCharm - Step 3](https://apidocs.io/illustration/python?workspaceFolder=Paypalseedpartnerapi-Python&projectName=paypalseedpartnerapi&step=openProject1)

### 2. Add a new Test Project

Create a new directory by right clicking on the solution name as shown below:

![Add a new project in PyCharm - Step 1](https://apidocs.io/illustration/python?workspaceFolder=Paypalseedpartnerapi-Python&projectName=paypalseedpartnerapi&step=createDirectory)

Name the directory as "test".

![Add a new project in PyCharm - Step 2](https://apidocs.io/illustration/python?workspaceFolder=Paypalseedpartnerapi-Python&step=nameDirectory)

Add a python file to this project.

![Add a new project in PyCharm - Step 3](https://apidocs.io/illustration/python?workspaceFolder=Paypalseedpartnerapi-Python&projectName=paypalseedpartnerapi&step=createFile)

Name it "testSDK".

![Add a new project in PyCharm - Step 4](https://apidocs.io/illustration/python?workspaceFolder=Paypalseedpartnerapi-Python&projectName=paypalseedpartnerapi&step=nameFile)

In your python file you will be required to import the generated python library using the following code lines

```python
from paypalseedpartnerapi.paypalseedpartnerapi_client import PaypalseedpartnerapiClient
```

![Add a new project in PyCharm - Step 5](https://apidocs.io/illustration/python?workspaceFolder=Paypalseedpartnerapi-Python&projectName=paypalseedpartnerapi&libraryName=paypalseedpartnerapi.paypalseedpartnerapi_client&className=PaypalseedpartnerapiClient&step=projectFiles)

After this you can write code to instantiate an API client object, get a controller object and  make API calls. Sample code is given in the subsequent sections.

### 3. Run the Test Project

To run the file within your test project, right click on your Python file inside your Test project and click on `Run`

![Run Test Project - Step 1](https://apidocs.io/illustration/python?workspaceFolder=Paypalseedpartnerapi-Python&projectName=paypalseedpartnerapi&libraryName=paypalseedpartnerapi.paypalseedpartnerapi_client&className=PaypalseedpartnerapiClient&step=runProject)

## Test the SDK

You can test the generated SDK and the server with test cases. `unittest` is used as the testing framework and `pytest` is used as the test runner. You can run the tests as follows:

Navigate to the root directory of the SDK and run the following commands


pip install -r test-requirements.txt
pytest


## Initialize the API Client

**_Note:_** Documentation for the client can be found [here.](doc/client.md)

The following parameters are configurable for the API Client:

| Parameter | Type | Description |
|  --- | --- | --- |
| environment | [`Environment`](README.md#environments) | The API environment. <br> **Default: `Environment.PRODUCTION`** |
| http_client_instance | `Union[Session, HttpClientProvider]` | The Http Client passed from the sdk user for making requests |
| override_http_client_configuration | `bool` | The value which determines to override properties of the passed Http Client from the sdk user |
| http_call_back | `HttpCallBack` | The callback value that is invoked before and after an HTTP call is made to an endpoint |
| timeout | `float` | The value to use for connection timeout. <br> **Default: 60** |
| max_retries | `int` | The number of times to retry an endpoint call if it fails. <br> **Default: 0** |
| backoff_factor | `float` | A backoff factor to apply between attempts after the second try. <br> **Default: 2** |
| retry_statuses | `Array of int` | The http statuses on which retry is to be done. <br> **Default: [408, 413, 429, 500, 502, 503, 504, 521, 522, 524, 408, 413, 429, 500, 502, 503, 504, 521, 522, 524]** |
| retry_methods | `Array of string` | The http methods on which retry is to be done. <br> **Default: ["GET", "PUT", "GET", "PUT"]** |
| proxy_settings | [`ProxySettings`](doc/proxy-settings.md) | Optional proxy configuration to route HTTP requests through a proxy server. |

The API client can be initialized as follows:

### Code-Based Client Initialization

```python
from paypalseedpartnerapi.configuration import Environment
from paypalseedpartnerapi.paypalseedpartnerapi_client import PaypalseedpartnerapiClient

client = PaypalseedpartnerapiClient(
    environment=Environment.PRODUCTION
)
```

### Environment-Based Client Initialization

```python
from paypalseedpartnerapi.paypalseedpartnerapi_client import PaypalseedpartnerapiClient

# Specify the path to your .env file if it’s located outside the project’s root directory.
client = PaypalseedpartnerapiClient.from_environment(dotenv_path='/path/to/.env')
```

See the [Environment-Based Client Initialization](doc/environment-based-client-initialization.md) section for details.

## Environments

The SDK can be configured to use a different environment for making API calls. Available environments are:

### Fields

| Name | Description |
|  --- | --- |
| PRODUCTION | **Default** Sandbox (demo data, no real money). The free host may need up to a minute to wake after idle. |
| ENVIRONMENT2 | Local development |

## List of APIs

* [Record-Public](doc/controllers/record-public.md)
* [Reserve](doc/controllers/reserve.md)

## SDK Infrastructure

### Configuration

* [ProxySettings](doc/proxy-settings.md)
* [Environment-Based Client Initialization](doc/environment-based-client-initialization.md)

### HTTP

* [HttpResponse](doc/http-response.md)
* [HttpRequest](doc/http-request.md)

### Utilities

* [ApiHelper](doc/api-helper.md)
* [HttpDateTime](doc/http-date-time.md)
* [RFC3339DateTime](doc/rfc3339-date-time.md)
* [UnixDateTime](doc/unix-date-time.md)

